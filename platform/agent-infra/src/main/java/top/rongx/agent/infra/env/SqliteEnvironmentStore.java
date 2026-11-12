package top.rongx.agent.infra.env;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import top.rongx.agent.domain.env.EffectiveEnvironment;
import top.rongx.agent.domain.env.EnvAddressing;
import top.rongx.agent.domain.env.EnvBinding;
import top.rongx.agent.domain.env.EnvResource;
import top.rongx.agent.domain.env.EnvTemplate;
import top.rongx.agent.domain.env.EnvVariable;
import top.rongx.agent.domain.env.EnvironmentStore;
import top.rongx.agent.domain.env.PortLease;
import top.rongx.agent.domain.env.RuntimeMode;
import top.rongx.agent.domain.env.SecretEntry;
import top.rongx.agent.domain.env.SecretRefs;
import top.rongx.agent.domain.env.ServiceLease;
import top.rongx.agent.domain.env.StackAdapter;
import top.rongx.agent.infra.security.SecretBox;

/**
 * 环境模板 / 中间件资源 / 环境变量 / 项目覆盖 / 绑定 / 端口租约的 SQLite 实现。
 *
 * <p>与旧的 {@code project_env_vars} 共存：启动时把旧表迁移到
 * {@code project_env_overrides}（不删旧表，便于回滚）。
 */
@Repository
public class SqliteEnvironmentStore implements EnvironmentStore {

    private static final Logger log = LoggerFactory.getLogger(SqliteEnvironmentStore.class);

    private static final String SCHEMA = """
            CREATE TABLE IF NOT EXISTS env_templates (
                env            TEXT PRIMARY KEY,
                network        TEXT,
                default_target TEXT NOT NULL DEFAULT 'docker',
                default_port   INTEGER NOT NULL DEFAULT 8080,
                health_path    TEXT NOT NULL DEFAULT '/healthz',
                runtime_mode   TEXT,
                namespace      TEXT,
                server_id      TEXT,
                updated_at     INTEGER NOT NULL
            );
            CREATE TABLE IF NOT EXISTS env_resources (
                id            TEXT PRIMARY KEY,
                env           TEXT NOT NULL,
                kind          TEXT NOT NULL,
                name          TEXT NOT NULL,
                host          TEXT NOT NULL,
                port          INTEGER NOT NULL,
                container_port INTEGER NOT NULL DEFAULT 0,
                internal_host TEXT,
                database_name TEXT,
                username      TEXT,
                password_enc  TEXT,
                password_iv   TEXT,
                network       TEXT,
                server_id     TEXT,
                source        TEXT,
                running       INTEGER NOT NULL DEFAULT 0,
                last_check_at INTEGER NOT NULL DEFAULT 0,
                last_error    TEXT,
                updated_at    INTEGER NOT NULL
            );
            CREATE INDEX IF NOT EXISTS idx_env_resources_env ON env_resources(env);
            CREATE TABLE IF NOT EXISTS env_vars (
                env        TEXT NOT NULL,
                var_key    TEXT NOT NULL,
                value_enc  TEXT NOT NULL,
                value_iv   TEXT NOT NULL,
                is_secret  INTEGER NOT NULL DEFAULT 1,
                source     TEXT,
                updated_at INTEGER NOT NULL,
                value_kind TEXT NOT NULL DEFAULT 'INLINE',
                secret_ref TEXT,
                PRIMARY KEY (env, var_key)
            );
            CREATE TABLE IF NOT EXISTS project_env_overrides (
                project_id TEXT NOT NULL,
                env        TEXT NOT NULL,
                var_key    TEXT NOT NULL,
                value_enc  TEXT NOT NULL,
                value_iv   TEXT NOT NULL,
                is_secret  INTEGER NOT NULL DEFAULT 1,
                updated_at INTEGER NOT NULL,
                value_kind TEXT NOT NULL DEFAULT 'INLINE',
                secret_ref TEXT,
                PRIMARY KEY (project_id, env, var_key)
            );
            CREATE TABLE IF NOT EXISTS secrets (
                id         TEXT PRIMARY KEY,
                project_id TEXT NOT NULL,
                env        TEXT NOT NULL,
                name       TEXT NOT NULL,
                value_enc  TEXT NOT NULL,
                value_iv   TEXT NOT NULL,
                version    INTEGER NOT NULL DEFAULT 1,
                created_at INTEGER NOT NULL,
                rotated_at INTEGER NOT NULL DEFAULT 0,
                UNIQUE (project_id, env, name)
            );
            CREATE TABLE IF NOT EXISTS env_bindings (
                id           TEXT PRIMARY KEY,
                project_id   TEXT NOT NULL,
                env          TEXT NOT NULL,
                mapping_json TEXT NOT NULL,
                mapping_hash TEXT NOT NULL,
                created_by   TEXT,
                confirmed_by TEXT,
                confirmed_at INTEGER NOT NULL DEFAULT 0,
                expires_at   INTEGER NOT NULL DEFAULT 0,
                status       TEXT NOT NULL DEFAULT 'DRAFT',
                created_at   INTEGER NOT NULL
            );
            CREATE TABLE IF NOT EXISTS deploy_port_leases (
                project_id     TEXT NOT NULL,
                env            TEXT NOT NULL,
                container_port INTEGER NOT NULL,
                host_port      INTEGER NOT NULL,
                run_id         TEXT,
                updated_at     INTEGER NOT NULL,
                PRIMARY KEY (project_id, env, container_port)
            );
            CREATE TABLE IF NOT EXISTS project_service_leases (
                project_id TEXT NOT NULL,
                env        TEXT NOT NULL,
                service    TEXT NOT NULL,
                host_port  INTEGER NOT NULL,
                status     TEXT NOT NULL DEFAULT 'STOPPED',
                pid        INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL,
                PRIMARY KEY (project_id, env, service)
            );
            """;

    private final Path dbFile;
    private final SecretBox secretBox;

    public SqliteEnvironmentStore(
            @Value("${agent.workspace.root:./data/workspaces}") String workspaceRoot,
            SecretBox secretBox) {
        this.dbFile = Path.of(workspaceRoot).toAbsolutePath().normalize()
                .resolve("_platform").resolve("projects.db");
        this.secretBox = secretBox;
        initSchema();
    }

    private void initSchema() {
        try (Connection conn = open(); Statement st = conn.createStatement()) {
            for (String ddl : SCHEMA.split(";")) {
                if (!ddl.isBlank()) {
                    st.executeUpdate(ddl);
                }
            }
            ensureColumns(st);
            seedTemplates(st);
            migrateLegacy(st);
        } catch (SQLException e) {
            throw new IllegalStateException("环境表初始化失败：" + e.getMessage(), e);
        }
    }

    /** 老库补列（SQLite 不支持 ADD COLUMN IF NOT EXISTS）。 */
    private void ensureColumns(Statement st) throws SQLException {
        ensureColumn(st, "env_templates", "runtime_mode", "runtime_mode TEXT");
        ensureColumn(st, "env_templates", "namespace", "namespace TEXT");
        ensureColumn(st, "env_templates", "server_id", "server_id TEXT");
        ensureColumn(st, "env_resources", "container_port",
                "container_port INTEGER NOT NULL DEFAULT 0");
        ensureColumn(st, "env_resources", "internal_host", "internal_host TEXT");
        ensureColumn(st, "env_vars", "value_kind", "value_kind TEXT NOT NULL DEFAULT 'INLINE'");
        ensureColumn(st, "env_vars", "secret_ref", "secret_ref TEXT");
        ensureColumn(st, "project_env_overrides", "value_kind",
                "value_kind TEXT NOT NULL DEFAULT 'INLINE'");
        ensureColumn(st, "project_env_overrides", "secret_ref", "secret_ref TEXT");
    }

    private void ensureColumn(Statement st, String table, String column, String ddl)
            throws SQLException {
        try (ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) {
                    return;
                }
            }
        }
        st.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + ddl);
    }

    private void seedTemplates(Statement st) throws SQLException {
        long now = System.currentTimeMillis();
        String[][] rows = {
                {"dev", "host", "8080", "/healthz", "LOCAL_DEV", "agent-dev", null},
                {"test", "docker", "8080", "/healthz", "LOCAL_TEST", "agent-test", "agent-test-net"},
                {"prod", "server", "8080", "/healthz", "SERVER", "agent-prod", "agent-prod-net"},
        };
        for (String[] r : rows) {
            st.executeUpdate("""
                    INSERT OR IGNORE INTO env_templates
                        (env, network, default_target, default_port, health_path,
                         runtime_mode, namespace, server_id, updated_at)
                    VALUES ('%s', %s, '%s', %s, '%s', '%s', '%s', NULL, %d)
                    """.formatted(r[0], r[6] == null ? "NULL" : "'" + r[6] + "'",
                    r[1], r[2], r[3], r[4], r[5], now));
        }
    }

    /** 旧表 project_env_vars → project_env_overrides；旧表不存在时跳过。 */
    private void migrateLegacy(Statement st) throws SQLException {
        try (ResultSet rs = st.executeQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name='project_env_vars'")) {
            if (!rs.next()) {
                return;
            }
        }
        st.executeUpdate("""
                INSERT OR IGNORE INTO project_env_overrides
                    (project_id, env, var_key, value_enc, value_iv, is_secret, updated_at)
                SELECT project_id, env, var_key, value_enc, value_iv, is_secret, updated_at
                FROM project_env_vars
                """);
    }

    private Connection open() throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + dbFile);
    }

    // ---------------------------------------------------------------- 模板

    @Override
    public List<EnvTemplate> templates() {
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement(
                     "SELECT env, network, default_target, default_port, health_path, "
                             + "runtime_mode, namespace, server_id, updated_at "
                             + "FROM env_templates ORDER BY CASE env WHEN 'dev' THEN 1 "
                             + "WHEN 'test' THEN 2 ELSE 3 END")) {
            try (ResultSet rs = st.executeQuery()) {
                List<EnvTemplate> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(mapTemplate(rs));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("环境模板查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public Optional<EnvTemplate> template(String env) {
        requireEnv(env);
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement(
                     "SELECT env, network, default_target, default_port, health_path, "
                             + "runtime_mode, namespace, server_id, updated_at "
                             + "FROM env_templates WHERE env = ?")) {
            st.setString(1, env);
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() ? Optional.of(mapTemplate(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("环境模板查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public void saveTemplate(EnvTemplate t) {
        requireEnv(t.env());
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement("""
                     INSERT INTO env_templates
                         (env, network, default_target, default_port, health_path,
                          runtime_mode, namespace, server_id, updated_at)
                     VALUES (?,?,?,?,?,?,?,?,?)
                     ON CONFLICT(env) DO UPDATE SET
                         network=excluded.network,
                         default_target=excluded.default_target,
                         default_port=excluded.default_port,
                         health_path=excluded.health_path,
                         runtime_mode=excluded.runtime_mode,
                         namespace=excluded.namespace,
                         server_id=excluded.server_id,
                         updated_at=excluded.updated_at
                     """)) {
            st.setString(1, t.env());
            st.setString(2, blankToNull(t.network()));
            st.setString(3, t.defaultTarget() == null || t.defaultTarget().isBlank()
                    ? "docker" : t.defaultTarget());
            st.setInt(4, t.defaultPort() <= 0 ? 8080 : t.defaultPort());
            st.setString(5, t.healthPath() == null || t.healthPath().isBlank()
                    ? "/healthz" : t.healthPath());
            st.setString(6, t.runtimeMode() == null || t.runtimeMode().isBlank()
                    ? RuntimeMode.defaultFor(t.env()).name() : t.runtimeMode());
            st.setString(7, t.namespace() == null || t.namespace().isBlank()
                    ? EnvTemplate.defaultNamespace(t.env()) : t.namespace());
            st.setString(8, blankToNull(t.serverId()));
            st.setLong(9, System.currentTimeMillis());
            st.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("环境模板保存失败：" + e.getMessage(), e);
        }
    }

    private EnvTemplate mapTemplate(ResultSet rs) throws SQLException {
        return new EnvTemplate(rs.getString("env"), rs.getString("network"),
                rs.getString("default_target"), rs.getInt("default_port"),
                rs.getString("health_path"), rs.getString("runtime_mode"),
                rs.getString("namespace"), rs.getString("server_id"),
                rs.getLong("updated_at"));
    }

    // ---------------------------------------------------------------- 资源

    @Override
    public List<EnvResource> resources(String env) {
        requireEnv(env);
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement(
                     "SELECT id, env, kind, name, host, port, container_port, internal_host, "
                             + "database_name, username, "
                             + "password_enc, password_iv, network, server_id, source, "
                             + "running, last_check_at, last_error, updated_at "
                             + "FROM env_resources WHERE env = ? ORDER BY kind, name")) {
            st.setString(1, env);
            try (ResultSet rs = st.executeQuery()) {
                List<EnvResource> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(mapResource(rs));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("中间件资源查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public Optional<EnvResource> resource(String id) {
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement(
                     "SELECT id, env, kind, name, host, port, container_port, internal_host, "
                             + "database_name, username, "
                             + "password_enc, password_iv, network, server_id, source, "
                             + "running, last_check_at, last_error, updated_at "
                             + "FROM env_resources WHERE id = ?")) {
            st.setString(1, id);
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() ? Optional.of(mapResource(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("中间件资源查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public EnvResource saveResource(EnvResource r) {
        requireEnv(r.env());
        String id = r.id() == null || r.id().isBlank() ? newId("res") : r.id();
        SecretBox.Sealed sealed = r.password() == null || r.password().isEmpty()
                ? null : secretBox.encrypt(r.password());
        long now = System.currentTimeMillis();
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement("""
                     INSERT INTO env_resources
                         (id, env, kind, name, host, port, container_port, internal_host,
                          database_name, username,
                          password_enc, password_iv, network, server_id, source,
                          running, last_check_at, last_error, updated_at)
                     VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                     ON CONFLICT(id) DO UPDATE SET
                         env=excluded.env, kind=excluded.kind, name=excluded.name,
                         host=excluded.host, port=excluded.port,
                         container_port=excluded.container_port,
                         internal_host=excluded.internal_host,
                         database_name=excluded.database_name, username=excluded.username,
                         network=excluded.network, server_id=excluded.server_id,
                         source=excluded.source, running=excluded.running,
                         last_check_at=excluded.last_check_at, last_error=excluded.last_error,
                         updated_at=excluded.updated_at
                         %s
                     """.formatted(sealed == null ? ""
                    : ", password_enc=excluded.password_enc, password_iv=excluded.password_iv"))) {
            int i = 1;
            st.setString(i++, id);
            st.setString(i++, r.env());
            st.setString(i++, r.kind());
            st.setString(i++, r.name() == null || r.name().isBlank() ? r.kind() : r.name());
            st.setString(i++, r.host());
            st.setInt(i++, r.port());
            st.setInt(i++, r.containerPort());
            st.setString(i++, blankToNull(r.internalHost()));
            st.setString(i++, blankToNull(r.databaseName()));
            st.setString(i++, blankToNull(r.username()));
            st.setString(i++, sealed == null ? null : sealed.cipherB64());
            st.setString(i++, sealed == null ? null : sealed.ivB64());
            st.setString(i++, blankToNull(r.network()));
            st.setString(i++, blankToNull(r.serverId()));
            st.setString(i++, r.source() == null ? "MANUAL" : r.source());
            st.setInt(i++, r.lastCheckAt() > 0 && r.running() ? 1 : 0);
            st.setLong(i++, r.lastCheckAt());
            st.setString(i++, blankToNull(r.lastError()));
            st.setLong(i, now);
            st.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("中间件资源保存失败：" + e.getMessage(), e);
        }
        return resource(id).orElseThrow();
    }

    @Override
    public void deleteResource(String id) {
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement("DELETE FROM env_resources WHERE id = ?")) {
            st.setString(1, id);
            st.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("中间件资源删除失败：" + e.getMessage(), e);
        }
    }

    @Override
    public void recordHealth(String id, boolean running, String error) {
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement("""
                     UPDATE env_resources SET running=?, last_check_at=?, last_error=?, updated_at=?
                     WHERE id=?
                     """)) {
            st.setInt(1, running ? 1 : 0);
            st.setLong(2, System.currentTimeMillis());
            st.setString(3, blankToNull(error));
            st.setLong(4, System.currentTimeMillis());
            st.setString(5, id);
            st.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("健康检查结果写入失败：" + e.getMessage(), e);
        }
    }

    private EnvResource mapResource(ResultSet rs) throws SQLException {
        String enc = rs.getString("password_enc");
        String iv = rs.getString("password_iv");
        String password = null;
        if (enc != null && iv != null) {
            try {
                password = secretBox.decrypt(enc, iv);
            } catch (RuntimeException e) {
                log.warn("中间件密码解密失败 id={}：{}", rs.getString("id"), e.getMessage());
            }
        }
        return new EnvResource(rs.getString("id"), rs.getString("env"), rs.getString("kind"),
                rs.getString("name"), rs.getString("host"), rs.getInt("port"),
                rs.getInt("container_port"), rs.getString("internal_host"),
                rs.getString("database_name"), rs.getString("username"), password,
                rs.getString("network"), rs.getString("server_id"), rs.getString("source"),
                rs.getInt("running") == 1, rs.getLong("last_check_at"), rs.getString("last_error"));
    }

    // ---------------------------------------------------------------- 环境变量

    @Override
    public List<EnvVariable> envVars(String env) {
        requireEnv(env);
        Map<String, String> plain = plainEnvVars(env);
        List<EnvVariable> out = new ArrayList<>();
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement(
                     "SELECT var_key, is_secret, source, updated_at, value_kind, secret_ref "
                             + "FROM env_vars WHERE env = ? ORDER BY var_key")) {
            st.setString(1, env);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    String key = rs.getString("var_key");
                    boolean secret = rs.getInt("is_secret") == 1;
                    String kind = rs.getString("value_kind");
                    String ref = rs.getString("secret_ref");
                    String value = plain.getOrDefault(key, "");
                    String shown = EnvVariable.KIND_SECRET_REF.equals(kind) && ref != null
                            ? "secret://" + ref
                            : secret ? secretBox.mask(value) : value;
                    out.add(new EnvVariable(null, env, key, secret, shown,
                            rs.getString("source") == null ? "TEMPLATE" : rs.getString("source"),
                            rs.getLong("updated_at"), kind, ref));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("环境变量查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public EnvVariable saveEnvVar(String env, String key, String value, boolean secret, String source) {
        requireEnv(env);
        requireKey(key);
        long now = System.currentTimeMillis();
        String ref = SecretRefs.whole(value);
        boolean refMode = ref != null;
        try (Connection conn = open()) {
            boolean keep = value == null || value.isEmpty();
            if (keep && !exists(conn, "env_vars", "env", env, "var_key", key)) {
                throw new IllegalArgumentException("新变量必须提供值：" + key);
            }
            if (keep) {
                try (PreparedStatement st = conn.prepareStatement(
                        "UPDATE env_vars SET is_secret=?, source=?, updated_at=? WHERE env=? AND var_key=?")) {
                    st.setInt(1, secret ? 1 : 0);
                    st.setString(2, source == null ? "TEMPLATE" : source);
                    st.setLong(3, now);
                    st.setString(4, env);
                    st.setString(5, key);
                    st.executeUpdate();
                }
            } else {
                SecretBox.Sealed sealed = secretBox.encrypt(refMode ? "" : value);
                try (PreparedStatement st = conn.prepareStatement("""
                        INSERT INTO env_vars
                            (env, var_key, value_enc, value_iv, is_secret, source, updated_at,
                             value_kind, secret_ref)
                        VALUES (?,?,?,?,?,?,?,?,?)
                        ON CONFLICT(env, var_key) DO UPDATE SET
                            value_enc=excluded.value_enc, value_iv=excluded.value_iv,
                            is_secret=excluded.is_secret, source=excluded.source,
                            updated_at=excluded.updated_at, value_kind=excluded.value_kind,
                            secret_ref=excluded.secret_ref
                        """)) {
                    st.setString(1, env);
                    st.setString(2, key);
                    st.setString(3, sealed.cipherB64());
                    st.setString(4, sealed.ivB64());
                    st.setInt(5, refMode || secret ? 1 : 0);
                    st.setString(6, source == null ? "TEMPLATE" : source);
                    st.setLong(7, now);
                    st.setString(8, refMode ? EnvVariable.KIND_SECRET_REF : EnvVariable.KIND_INLINE);
                    st.setString(9, ref);
                    st.executeUpdate();
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("环境变量保存失败：" + e.getMessage(), e);
        }
        return envVars(env).stream().filter(v -> v.key().equals(key)).findFirst().orElseThrow();
    }

    @Override
    public Optional<String> envVarValue(String env, String key) {
        requireEnv(env);
        requireKey(key);
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement(
                     "SELECT value_enc, value_iv FROM env_vars WHERE env = ? AND var_key = ?")) {
            st.setString(1, env);
            st.setString(2, key);
            try (ResultSet rs = st.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(secretBox.decrypt(rs.getString("value_enc"),
                        rs.getString("value_iv")));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("环境变量读取失败：" + e.getMessage(), e);
        }
    }

    /**
     * 资源连接信息 → 环境模板变量（source=RESOURCE）。
     *
     * <p>不覆盖人工维护的变量；值没变化时不重写，避免刷新页面就改 updated_at。
     */
    @Override
    public int syncResourceVars(String env) {
        requireEnv(env);
        Map<String, String> derived = StackAdapter.derive(
                EnvAddressing.resolve(resources(env), template(env).orElse(null)));
        if (derived.isEmpty()) {
            return 0;
        }
        Map<String, String> current = plainEnvVars(env);
        Map<String, String> sources = envVarSources(env);
        int written = 0;
        for (Map.Entry<String, String> e : derived.entrySet()) {
            String key = e.getKey();
            String source = sources.get(key);
            if (source != null && !"RESOURCE".equals(source)) {
                // 人工变量优先，资源只补空缺
                continue;
            }
            if (e.getValue().equals(current.get(key))) {
                continue;
            }
            saveEnvVar(env, key, e.getValue(), looksSecret(key), "RESOURCE");
            written++;
        }
        return written;
    }

    /** 变量名 → 来源（TEMPLATE / RESOURCE）。 */
    private Map<String, String> envVarSources(String env) {
        Map<String, String> out = new LinkedHashMap<>();
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement(
                     "SELECT var_key, source FROM env_vars WHERE env = ?")) {
            st.setString(1, env);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getString("var_key"),
                            rs.getString("source") == null ? "TEMPLATE" : rs.getString("source"));
                }
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException("环境变量来源查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public void deleteEnvVar(String env, String key) {
        requireEnv(env);
        requireKey(key);
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement(
                     "DELETE FROM env_vars WHERE env=? AND var_key=?")) {
            st.setString(1, env);
            st.setString(2, key);
            st.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("环境变量删除失败：" + e.getMessage(), e);
        }
    }

    private Map<String, String> plainEnvVars(String env) {
        return plainMap("SELECT var_key, value_enc, value_iv FROM env_vars WHERE env = ?", env);
    }

    private Map<String, String> plainOverrides(String projectId, String env) {
        Map<String, String> out = new LinkedHashMap<>();
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement(
                     "SELECT var_key, value_enc, value_iv FROM project_env_overrides "
                             + "WHERE project_id = ? AND env = ? ORDER BY var_key")) {
            st.setString(1, projectId);
            st.setString(2, env);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getString("var_key"), secretBox.decrypt(
                            rs.getString("value_enc"), rs.getString("value_iv")));
                }
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException("项目覆盖查询失败：" + e.getMessage(), e);
        }
    }

    private Map<String, String> plainMap(String sql, String env) {
        Map<String, String> out = new LinkedHashMap<>();
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement(sql)) {
            st.setString(1, env);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getString("var_key"), secretBox.decrypt(
                            rs.getString("value_enc"), rs.getString("value_iv")));
                }
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException("环境变量读取失败：" + e.getMessage(), e);
        }
    }

    // ---------------------------------------------------------------- 项目覆盖

    @Override
    public List<EnvVariable> overrides(String projectId, String env) {
        requireProject(projectId);
        requireEnv(env);
        Map<String, String> plain = plainOverrides(projectId, env);
        List<EnvVariable> out = new ArrayList<>();
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement(
                     "SELECT var_key, is_secret, updated_at, value_kind, secret_ref "
                             + "FROM project_env_overrides "
                             + "WHERE project_id=? AND env=? ORDER BY var_key")) {
            st.setString(1, projectId);
            st.setString(2, env);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    String key = rs.getString("var_key");
                    boolean secret = rs.getInt("is_secret") == 1;
                    String kind = rs.getString("value_kind");
                    String ref = rs.getString("secret_ref");
                    String value = plain.getOrDefault(key, "");
                    String shown = EnvVariable.KIND_SECRET_REF.equals(kind) && ref != null
                            ? "secret://" + ref
                            : secret ? secretBox.mask(value) : value;
                    out.add(new EnvVariable(projectId, env, key, secret, shown, "OVERRIDE",
                            rs.getLong("updated_at"), kind, ref));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("项目覆盖查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public EnvVariable saveOverride(String projectId, String env, String key, String value, boolean secret) {
        requireProject(projectId);
        requireEnv(env);
        requireKey(key);
        long now = System.currentTimeMillis();
        String ref = SecretRefs.whole(value);
        boolean refMode = ref != null;
        try (Connection conn = open()) {
            boolean keep = value == null || value.isEmpty();
            if (keep && !overrideExists(conn, projectId, env, key)) {
                throw new IllegalArgumentException("新覆盖项必须提供值：" + key);
            }
            if (keep) {
                try (PreparedStatement st = conn.prepareStatement("""
                        UPDATE project_env_overrides SET is_secret=?, updated_at=?
                        WHERE project_id=? AND env=? AND var_key=?
                        """)) {
                    st.setInt(1, secret ? 1 : 0);
                    st.setLong(2, now);
                    st.setString(3, projectId);
                    st.setString(4, env);
                    st.setString(5, key);
                    st.executeUpdate();
                }
            } else {
                SecretBox.Sealed sealed = secretBox.encrypt(refMode ? "" : value);
                try (PreparedStatement st = conn.prepareStatement("""
                        INSERT INTO project_env_overrides
                            (project_id, env, var_key, value_enc, value_iv, is_secret, updated_at,
                             value_kind, secret_ref)
                        VALUES (?,?,?,?,?,?,?,?,?)
                        ON CONFLICT(project_id, env, var_key) DO UPDATE SET
                            value_enc=excluded.value_enc, value_iv=excluded.value_iv,
                            is_secret=excluded.is_secret, updated_at=excluded.updated_at,
                            value_kind=excluded.value_kind, secret_ref=excluded.secret_ref
                        """)) {
                    st.setString(1, projectId);
                    st.setString(2, env);
                    st.setString(3, key);
                    st.setString(4, sealed.cipherB64());
                    st.setString(5, sealed.ivB64());
                    st.setInt(6, refMode || secret ? 1 : 0);
                    st.setLong(7, now);
                    st.setString(8, refMode ? EnvVariable.KIND_SECRET_REF : EnvVariable.KIND_INLINE);
                    st.setString(9, ref);
                    st.executeUpdate();
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("项目覆盖保存失败：" + e.getMessage(), e);
        }
        return overrides(projectId, env).stream()
                .filter(v -> v.key().equals(key)).findFirst().orElseThrow();
    }

    @Override
    public void deleteOverride(String projectId, String env, String key) {
        requireProject(projectId);
        requireEnv(env);
        requireKey(key);
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement(
                     "DELETE FROM project_env_overrides WHERE project_id=? AND env=? AND var_key=?")) {
            st.setString(1, projectId);
            st.setString(2, env);
            st.setString(3, key);
            st.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("项目覆盖删除失败：" + e.getMessage(), e);
        }
    }

    // ---------------------------------------------------------------- 密钥引用

    @Override
    public List<SecretEntry> secrets(String projectId, String env) {
        requireProject(projectId);
        requireEnv(env);
        List<SecretEntry> out = new ArrayList<>();
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement("""
                     SELECT project_id, env, name, version, rotated_at, created_at
                     FROM secrets WHERE project_id=? AND env=? ORDER BY name
                     """)) {
            st.setString(1, projectId);
            st.setString(2, env);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    long updated = Math.max(rs.getLong("rotated_at"), rs.getLong("created_at"));
                    out.add(new SecretEntry(rs.getString("project_id"), rs.getString("env"),
                            rs.getString("name"), rs.getInt("version"), updated));
                }
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException("密钥查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public SecretEntry saveSecret(String projectId, String env, String name, String value) {
        requireProject(projectId);
        requireEnv(env);
        requireSecretName(name);
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("密钥值不能为空：" + name);
        }
        long now = System.currentTimeMillis();
        SecretBox.Sealed sealed = secretBox.encrypt(value);
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement("""
                     INSERT INTO secrets
                         (id, project_id, env, name, value_enc, value_iv, version, created_at, rotated_at)
                     VALUES (?,?,?,?,?,?,1,?,0)
                     ON CONFLICT(project_id, env, name) DO UPDATE SET
                         value_enc=excluded.value_enc, value_iv=excluded.value_iv,
                         version=secrets.version + 1, rotated_at=excluded.created_at
                     """)) {
            st.setString(1, java.util.UUID.randomUUID().toString());
            st.setString(2, projectId);
            st.setString(3, env);
            st.setString(4, name);
            st.setString(5, sealed.cipherB64());
            st.setString(6, sealed.ivB64());
            st.setLong(7, now);
            st.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("密钥保存失败：" + e.getMessage(), e);
        }
        return secrets(projectId, env).stream()
                .filter(s -> s.name().equals(name)).findFirst().orElseThrow();
    }

    @Override
    public SecretEntry rotateSecret(String projectId, String env, String name, String value) {
        requireProject(projectId);
        requireEnv(env);
        requireSecretName(name);
        if (secretValue(projectId, env, name).isEmpty()) {
            throw new IllegalArgumentException("密钥不存在，无法轮换：" + name);
        }
        return saveSecret(projectId, env, name, value);
    }

    @Override
    public void deleteSecret(String projectId, String env, String name) {
        requireProject(projectId);
        requireEnv(env);
        requireSecretName(name);
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement(
                     "DELETE FROM secrets WHERE project_id=? AND env=? AND name=?")) {
            st.setString(1, projectId);
            st.setString(2, env);
            st.setString(3, name);
            st.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("密钥删除失败：" + e.getMessage(), e);
        }
    }

    @Override
    public Optional<String> secretValue(String projectId, String env, String name) {
        requireProject(projectId);
        requireEnv(env);
        requireSecretName(name);
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement(
                     "SELECT value_enc, value_iv FROM secrets WHERE project_id=? AND env=? AND name=?")) {
            st.setString(1, projectId);
            st.setString(2, env);
            st.setString(3, name);
            try (ResultSet rs = st.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(secretBox.decrypt(rs.getString("value_enc"),
                        rs.getString("value_iv")));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("密钥读取失败：" + e.getMessage(), e);
        }
    }

    private int secretVersion(String projectId, String env, String name) {
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement(
                     "SELECT version FROM secrets WHERE project_id=? AND env=? AND name=?")) {
            st.setString(1, projectId);
            st.setString(2, env);
            st.setString(3, name);
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() ? rs.getInt("version") : 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("密钥版本读取失败：" + e.getMessage(), e);
        }
    }

    private void requireSecretName(String name) {
        if (!SecretRefs.validName(name)) {
            throw new IllegalArgumentException("密钥引用名只允许字母、数字、点、下划线与短横线：" + name);
        }
    }

    private record StoredValue(String value, String kind, String ref) {
    }

    private Map<String, StoredValue> storedEnvVars(String env) {
        return storedValues("SELECT var_key, value_enc, value_iv, value_kind, secret_ref "
                + "FROM env_vars WHERE env = ?", env);
    }

    private Map<String, StoredValue> storedOverrides(String projectId, String env) {
        Map<String, StoredValue> out = new LinkedHashMap<>();
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement(
                     "SELECT var_key, value_enc, value_iv, value_kind, secret_ref "
                             + "FROM project_env_overrides WHERE project_id=? AND env=? "
                             + "ORDER BY var_key")) {
            st.setString(1, projectId);
            st.setString(2, env);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    String kind = rs.getString("value_kind");
                    String ref = rs.getString("secret_ref");
                    String value = EnvVariable.KIND_SECRET_REF.equals(kind)
                            ? "" : secretBox.decrypt(rs.getString("value_enc"), rs.getString("value_iv"));
                    out.put(rs.getString("var_key"), new StoredValue(value, kind, ref));
                }
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException("项目覆盖查询失败：" + e.getMessage(), e);
        }
    }

    private Map<String, StoredValue> storedValues(String sql, String env) {
        Map<String, StoredValue> out = new LinkedHashMap<>();
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement(sql)) {
            st.setString(1, env);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    String kind = rs.getString("value_kind");
                    String ref = rs.getString("secret_ref");
                    String value = EnvVariable.KIND_SECRET_REF.equals(kind)
                            ? "" : secretBox.decrypt(rs.getString("value_enc"), rs.getString("value_iv"));
                    out.put(rs.getString("var_key"), new StoredValue(value, kind, ref));
                }
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException("环境变量读取失败：" + e.getMessage(), e);
        }
    }

    // ---------------------------------------------------------------- 有效环境

    @Override
    public EffectiveEnvironment effective(String projectId, String env) {
        requireProject(projectId);
        requireEnv(env);
        EnvTemplate template = template(env).orElse(
                new EnvTemplate(env, null, "docker", 8080, "/healthz", 0));
        // 同一份登记值按运行形态解析：dev=宿主机发布端口，test=容器名+容器内端口，server=服务器地址。
        List<EnvResource> resources = EnvAddressing.resolve(resources(env), template);
        Map<String, String> derived = StackAdapter.derive(resources);
        // RESOURCE 来源只是"资源连接信息的落库镜像"，派生值才是权威：
        // 资源被编辑后即使还没同步变量，也不会把旧主机注入容器。
        Map<String, StoredValue> allVars = storedEnvVars(env);
        Map<String, String> sources = envVarSources(env);
        Map<String, StoredValue> templateVars = new LinkedHashMap<>();
        for (Map.Entry<String, StoredValue> e : allVars.entrySet()) {
            if (!"RESOURCE".equals(sources.get(e.getKey()))) {
                templateVars.put(e.getKey(), e.getValue());
            }
        }
        Map<String, StoredValue> overrides = storedOverrides(projectId, env);

        Map<String, String> values = new LinkedHashMap<>();
        Map<String, String> refs = new LinkedHashMap<>();
        values.putAll(derived);
        for (Map.Entry<String, StoredValue> e : templateVars.entrySet()) {
            values.put(e.getKey(), e.getValue().value());
            if (EnvVariable.KIND_SECRET_REF.equals(e.getValue().kind())
                    && e.getValue().ref() != null) {
                refs.put(e.getKey(), e.getValue().ref());
            }
        }
        for (Map.Entry<String, StoredValue> e : overrides.entrySet()) {
            values.put(e.getKey(), e.getValue().value());
            if (EnvVariable.KIND_SECRET_REF.equals(e.getValue().kind())
                    && e.getValue().ref() != null) {
                refs.put(e.getKey(), e.getValue().ref());
            } else {
                refs.remove(e.getKey());
            }
        }

        // 密钥解析：整值引用 + 嵌入占位符；缺失不抛异常，交给部署预检 fail-closed。
        java.util.Set<String> missingSecrets = new java.util.LinkedHashSet<>();
        java.util.Set<String> embeddedSecretKeys = new java.util.HashSet<>();
        Map<String, Integer> secretVersions = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : values.entrySet()) {
            String key = e.getKey();
            String ref = refs.get(key);
            if (ref != null) {
                Optional<String> secret = secretValue(projectId, env, ref);
                if (secret.isEmpty()) {
                    missingSecrets.add(ref);
                    values.put(key, "");
                } else {
                    values.put(key, secret.get());
                }
                secretVersions.put(key, secretVersion(projectId, env, ref));
                continue;
            }
            String raw = e.getValue();
            List<String> embedded = SecretRefs.embedded(raw);
            if (!embedded.isEmpty()) {
                embeddedSecretKeys.add(key);
                String resolved = raw;
                for (String name : embedded) {
                    Optional<String> secret = secretValue(projectId, env, name);
                    if (secret.isEmpty()) {
                        missingSecrets.add(name);
                        resolved = resolved.replace("${secret:" + name + "}", "");
                    } else {
                        resolved = resolved.replace("${secret:" + name + "}", secret.get());
                    }
                    secretVersions.put(key, secretVersion(projectId, env, name));
                }
                values.put(key, resolved);
            }
        }

        List<EnvVariable> vars = new ArrayList<>();
        for (Map.Entry<String, String> e : values.entrySet()) {
            String key = e.getKey();
            String value = e.getValue();
            String ref = refs.get(key);
            String source = overrides.containsKey(key) ? "OVERRIDE"
                    : templateVars.containsKey(key) ? "TEMPLATE" : "RESOURCE";
            boolean secret = ref != null || embeddedSecretKeys.contains(key)
                    || (overrides.containsKey(key)
                    ? isSecretOverride(projectId, env, key)
                    : templateVars.containsKey(key)
                    ? isSecretEnvVar(env, key)
                    : looksSecret(key));
            String shown;
            if (ref != null) {
                shown = "secret://" + ref;
            } else if (secret) {
                shown = secretBox.mask(value);
            } else {
                shown = value;
            }
            vars.add(new EnvVariable(projectId, env, key, secret, shown, source, 0,
                    ref == null ? EnvVariable.KIND_INLINE : EnvVariable.KIND_SECRET_REF, ref));
        }
        return new EffectiveEnvironment(projectId, env, template.network(),
                template.defaultPort(), template.healthPath(), vars, values, resources,
                templateVars.size() + derived.size(), overrides.size(), missingSecrets,
                secretVersions);
    }

    private boolean isSecretEnvVar(String env, String key) {
        return flag("SELECT is_secret FROM env_vars WHERE env=? AND var_key=?", env, key);
    }

    private boolean isSecretOverride(String projectId, String env, String key) {
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement(
                     "SELECT is_secret FROM project_env_overrides "
                             + "WHERE project_id=? AND env=? AND var_key=?")) {
            st.setString(1, projectId);
            st.setString(2, env);
            st.setString(3, key);
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() && rs.getInt(1) == 1;
            }
        } catch (SQLException e) {
            return true;
        }
    }

    private boolean flag(String sql, String... args) {
        try (Connection conn = open(); PreparedStatement st = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                st.setString(i + 1, args[i]);
            }
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() && rs.getInt(1) == 1;
            }
        } catch (SQLException e) {
            return true;
        }
    }

    private static boolean looksSecret(String key) {
        String k = key.toUpperCase(java.util.Locale.ROOT);
        return k.contains("PASSWORD") || k.contains("SECRET") || k.contains("TOKEN")
                || k.endsWith("_KEY") || k.contains("PRIVATE");
    }

    // ---------------------------------------------------------------- 绑定

    @Override
    public EnvBinding saveBinding(EnvBinding b) {
        requireProject(b.projectId());
        requireEnv(b.env());
        String id = b.id() == null || b.id().isBlank() ? newId("bind") : b.id();
        long now = System.currentTimeMillis();
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement("""
                     INSERT INTO env_bindings
                         (id, project_id, env, mapping_json, mapping_hash, created_by,
                          confirmed_by, confirmed_at, expires_at, status, created_at)
                     VALUES (?,?,?,?,?,?,?,?,?,?,?)
                     ON CONFLICT(id) DO UPDATE SET
                         mapping_json=excluded.mapping_json, mapping_hash=excluded.mapping_hash,
                         status=excluded.status, confirmed_by=excluded.confirmed_by,
                         confirmed_at=excluded.confirmed_at, expires_at=excluded.expires_at
                     """)) {
            int i = 1;
            st.setString(i++, id);
            st.setString(i++, b.projectId());
            st.setString(i++, b.env());
            st.setString(i++, b.mappingJson());
            st.setString(i++, b.mappingHash());
            st.setString(i++, b.createdBy());
            st.setString(i++, b.confirmedBy());
            st.setLong(i++, b.confirmedAt());
            st.setLong(i++, b.expiresAt());
            st.setString(i++, b.status() == null ? "DRAFT" : b.status());
            st.setLong(i, now);
            st.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("环境绑定保存失败：" + e.getMessage(), e);
        }
        return binding(id).orElseThrow();
    }

    @Override
    public Optional<EnvBinding> binding(String id) {
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement(
                     "SELECT id, project_id, env, mapping_json, mapping_hash, created_by, "
                             + "confirmed_by, confirmed_at, expires_at, status, created_at "
                             + "FROM env_bindings WHERE id = ?")) {
            st.setString(1, id);
            try (ResultSet rs = st.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new EnvBinding(
                        rs.getString("id"), rs.getString("project_id"), rs.getString("env"),
                        rs.getString("mapping_json"), rs.getString("mapping_hash"),
                        rs.getString("created_by"), rs.getString("confirmed_by"),
                        rs.getLong("confirmed_at"), rs.getLong("expires_at"),
                        rs.getString("status")));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("环境绑定查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public void confirmBinding(String id, String confirmedBy, long expiresAt) {
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement("""
                     UPDATE env_bindings
                     SET status='CONFIRMED', confirmed_by=?, confirmed_at=?, expires_at=?
                     WHERE id=?
                     """)) {
            st.setString(1, confirmedBy);
            st.setLong(2, System.currentTimeMillis());
            st.setLong(3, expiresAt);
            st.setString(4, id);
            st.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("环境绑定确认失败：" + e.getMessage(), e);
        }
    }

    // ---------------------------------------------------------------- 端口租约

    @Override
    public Optional<PortLease> lease(String projectId, String env, int containerPort) {
        requireProject(projectId);
        requireEnv(env);
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement(
                     "SELECT project_id, env, container_port, host_port, run_id, updated_at "
                             + "FROM deploy_port_leases "
                             + "WHERE project_id=? AND env=? AND container_port=?")) {
            st.setString(1, projectId);
            st.setString(2, env);
            st.setInt(3, containerPort);
            try (ResultSet rs = st.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new PortLease(projectId, env, containerPort,
                        rs.getInt("host_port"), rs.getString("run_id"), rs.getLong("updated_at")));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("端口租约查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public List<PortLease> leases(String projectId, String env) {
        requireProject(projectId);
        requireEnv(env);
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement(
                     "SELECT project_id, env, container_port, host_port, run_id, updated_at "
                             + "FROM deploy_port_leases "
                             + "WHERE project_id=? AND env=? ORDER BY container_port")) {
            st.setString(1, projectId);
            st.setString(2, env);
            try (ResultSet rs = st.executeQuery()) {
                List<PortLease> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(new PortLease(projectId, env, rs.getInt("container_port"),
                            rs.getInt("host_port"), rs.getString("run_id"),
                            rs.getLong("updated_at")));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("端口租约查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public void saveLease(PortLease lease) {
        requireProject(lease.projectId());
        requireEnv(lease.env());
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement("""
                     INSERT INTO deploy_port_leases
                         (project_id, env, container_port, host_port, run_id, updated_at)
                     VALUES (?,?,?,?,?,?)
                     ON CONFLICT(project_id, env, container_port) DO UPDATE SET
                         host_port=excluded.host_port, run_id=excluded.run_id,
                         updated_at=excluded.updated_at
                     """)) {
            st.setString(1, lease.projectId());
            st.setString(2, lease.env());
            st.setInt(3, lease.containerPort());
            st.setInt(4, lease.hostPort());
            st.setString(5, lease.runId());
            st.setLong(6, System.currentTimeMillis());
            st.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("端口租约保存失败：" + e.getMessage(), e);
        }
    }

    // ---------------------------------------------------------------- 本地服务租约

    @Override
    public List<ServiceLease> serviceLeases(String projectId, String env) {
        requireProject(projectId);
        requireEnv(env);
        return queryServiceLeases(
                "SELECT project_id, env, service, host_port, status, pid, updated_at "
                        + "FROM project_service_leases WHERE project_id=? AND env=? "
                        + "ORDER BY service", projectId, env);
    }

    @Override
    public List<ServiceLease> allServiceLeases(String env) {
        requireEnv(env);
        return queryServiceLeases(
                "SELECT project_id, env, service, host_port, status, pid, updated_at "
                        + "FROM project_service_leases WHERE env=? ORDER BY host_port", env);
    }

    private List<ServiceLease> queryServiceLeases(String sql, String... args) {
        try (Connection conn = open(); PreparedStatement st = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                st.setString(i + 1, args[i]);
            }
            try (ResultSet rs = st.executeQuery()) {
                List<ServiceLease> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(new ServiceLease(rs.getString("project_id"), rs.getString("env"),
                            rs.getString("service"), rs.getInt("host_port"),
                            rs.getString("status"), rs.getLong("pid"),
                            rs.getLong("updated_at")));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("本地服务租约查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public void saveServiceLease(ServiceLease lease) {
        requireProject(lease.projectId());
        requireEnv(lease.env());
        requireService(lease.service());
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement("""
                     INSERT INTO project_service_leases
                         (project_id, env, service, host_port, status, pid, updated_at)
                     VALUES (?,?,?,?,?,?,?)
                     ON CONFLICT(project_id, env, service) DO UPDATE SET
                         host_port=excluded.host_port, status=excluded.status,
                         pid=excluded.pid, updated_at=excluded.updated_at
                     """)) {
            st.setString(1, lease.projectId());
            st.setString(2, lease.env());
            st.setString(3, lease.service());
            st.setInt(4, lease.hostPort());
            st.setString(5, lease.status() == null ? "STOPPED" : lease.status());
            st.setLong(6, lease.pid());
            st.setLong(7, System.currentTimeMillis());
            st.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("本地服务租约保存失败：" + e.getMessage(), e);
        }
    }

    @Override
    public void deleteServiceLease(String projectId, String env, String service) {
        requireProject(projectId);
        requireEnv(env);
        requireService(service);
        try (Connection conn = open();
             PreparedStatement st = conn.prepareStatement(
                     "DELETE FROM project_service_leases "
                             + "WHERE project_id=? AND env=? AND service=?")) {
            st.setString(1, projectId);
            st.setString(2, env);
            st.setString(3, service);
            st.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("本地服务租约删除失败：" + e.getMessage(), e);
        }
    }

    // ---------------------------------------------------------------- 工具

    private boolean exists(Connection conn, String table, String col1, String val1,
                           String col2, String val2) throws SQLException {
        try (PreparedStatement st = conn.prepareStatement(
                "SELECT 1 FROM " + table + " WHERE " + col1 + "=? AND " + col2 + "=?")) {
            st.setString(1, val1);
            st.setString(2, val2);
            try (ResultSet rs = st.executeQuery()) {
                return rs.next();
            }
        }
    }

    private boolean overrideExists(Connection conn, String projectId, String env, String key)
            throws SQLException {
        try (PreparedStatement st = conn.prepareStatement(
                "SELECT 1 FROM project_env_overrides WHERE project_id=? AND env=? AND var_key=?")) {
            st.setString(1, projectId);
            st.setString(2, env);
            st.setString(3, key);
            try (ResultSet rs = st.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static String newId(String prefix) {
        return prefix + "-" + java.util.UUID.randomUUID().toString().substring(0, 8);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static void requireProject(String projectId) {
        if (projectId == null || !projectId.matches("[A-Za-z0-9_-]{1,32}")) {
            throw new IllegalArgumentException("非法项目 ID：" + projectId);
        }
    }

    private static void requireEnv(String env) {
        if (!List.of("dev", "test", "prod").contains(env)) {
            throw new IllegalArgumentException("环境只能是 dev / test / prod：" + env);
        }
    }

    private static void requireKey(String key) {
        if (key == null || !key.matches("[A-Za-z_][A-Za-z0-9_]{0,63}")) {
            throw new IllegalArgumentException("变量名不合法（需 [A-Za-z_][A-Za-z0-9_]*）：" + key);
        }
    }

    private static void requireService(String service) {
        if (!List.of(ServiceLease.BACKEND, ServiceLease.FRONTEND).contains(service)) {
            throw new IllegalArgumentException("服务只能是 backend / frontend：" + service);
        }
    }
}
