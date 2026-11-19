package top.rongx.agent.infra.env;

import jakarta.persistence.EntityManager;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import top.rongx.agent.domain.env.EffectiveEnvironment;
import top.rongx.agent.domain.env.EnvAddressing;
import top.rongx.agent.domain.env.EnvBinding;
import top.rongx.agent.domain.env.EnvNames;
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
import top.rongx.agent.domain.error.ErrorCode;
import top.rongx.agent.domain.error.EnvironmentException;
import top.rongx.agent.infra.persistence.PlatformDb;

/**
 * 环境模板 / 中间件资源 / 环境变量 / 项目覆盖 / 绑定 / 端口租约的 JPA 实现。
 *
 * <p>表结构由 {@code top.rongx.agent.infra.persistence.entity} 下的实体生成；
 * 连接、事务与 SQLite/PostgreSQL 方言由 Hibernate 负责。历史实现里的建表、
 * 补列与旧表迁移已移除，统一入口见 {@link PlatformDb}。
 */
@Repository
public class DbEnvironmentStore implements EnvironmentStore {

    private static final Logger log = LoggerFactory.getLogger(DbEnvironmentStore.class);

    private static final String TEMPLATE_COLUMNS = """
            env, network, default_target, default_port, health_path,
            runtime_mode, namespace, server_id, updated_at
            """;

    private static final String RESOURCE_COLUMNS = """
            id, env, kind, name, host, port, container_port, internal_host,
            database_name, username,
            password_enc, password_iv, network, server_id, source,
            running, last_check_at, last_error, updated_at
            """;

    private static final String BINDING_COLUMNS = """
            id, project_id, env, mapping_json, mapping_hash, created_by,
            confirmed_by, confirmed_at, expires_at, status, created_at
            """;

    /** 绑定历史默认/上限条数：卡片只展示最近的一批，避免长历史拖慢首屏。 */
    private static final int DEFAULT_BINDING_HISTORY = 20;
    private static final int MAX_BINDING_HISTORY = 200;

    private final PlatformDb db;
    private final SecretBox secretBox;

    @Autowired
    public DbEnvironmentStore(PlatformDb db, SecretBox secretBox) {
        this.db = db;
        this.secretBox = secretBox;
        seedTemplates();
    }

    /** 测试/独立进程：按工作区根目录定位统一库文件。 */
    public DbEnvironmentStore(
            @Value("${agent.workspace.root:./data/workspaces}") String workspaceRoot,
            SecretBox secretBox) {
        this(PlatformDb.sqlite(Path.of(workspaceRoot).toAbsolutePath().normalize()
                .resolve("_platform").resolve("platform.db")), secretBox);
    }

    private void seedTemplates() {
        long now = System.currentTimeMillis();
        String[][] rows = {
                {"dev", "host", "8080", "/healthz", "LOCAL_DEV", "agent-dev", null},
                {"test", "docker", "8080", "/healthz", "LOCAL_TEST", "agent-test", "agent-test-net"},
                {"prod", "server", "8080", "/healthz", "SERVER", "agent-prod", "agent-prod-net"},
        };
        try {
            db.inTx(em -> {
                for (String[] r : rows) {
                    PlatformDb.update(em, """
                            INSERT INTO env_templates
                                (env, network, default_target, default_port, health_path,
                                 runtime_mode, namespace, server_id, updated_at)
                            VALUES (?,?,?,?,?,?,?,?,?)
                            ON CONFLICT(env) DO NOTHING
                            """, r[0], r[6], r[1], Integer.parseInt(r[2]), r[3], r[4], r[5], null, now);
                }
                return null;
            });
        } catch (RuntimeException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "环境表初始化失败：" + e.getMessage(), e);
        }
    }

    // ---------------------------------------------------------------- 模板

    @Override
    public List<EnvTemplate> templates() {
        String sql = "SELECT " + TEMPLATE_COLUMNS + " FROM env_templates ORDER BY CASE env "
                + "WHEN 'dev' THEN 1 WHEN 'test' THEN 2 ELSE 3 END";
        try {
            return db.read(em -> PlatformDb.rows(em, sql).stream()
                    .map(DbEnvironmentStore::mapTemplate).toList());
        } catch (RuntimeException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "环境模板查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public Optional<EnvTemplate> template(String env) {
        requireEnv(env);
        try {
            return db.read(em -> PlatformDb.row(em,
                    "SELECT " + TEMPLATE_COLUMNS + " FROM env_templates WHERE env = ?", env)
                    .map(DbEnvironmentStore::mapTemplate));
        } catch (RuntimeException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "环境模板查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public void saveTemplate(EnvTemplate t) {
        requireEnv(t.env());
        try {
            db.run(em -> PlatformDb.update(em, """
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
                    """,
                    t.env(),
                    blankToNull(t.network()),
                    t.defaultTarget() == null || t.defaultTarget().isBlank()
                            ? "docker" : t.defaultTarget(),
                    t.defaultPort() <= 0 ? 8080 : t.defaultPort(),
                    t.healthPath() == null || t.healthPath().isBlank()
                            ? "/healthz" : t.healthPath(),
                    t.runtimeMode() == null || t.runtimeMode().isBlank()
                            ? RuntimeMode.defaultFor(t.env()).name() : t.runtimeMode(),
                    t.namespace() == null || t.namespace().isBlank()
                            ? EnvTemplate.defaultNamespace(t.env()) : t.namespace(),
                    blankToNull(t.serverId()),
                    System.currentTimeMillis()));
        } catch (RuntimeException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "环境模板保存失败：" + e.getMessage(), e);
        }
    }

    private static EnvTemplate mapTemplate(Object[] row) {
        return new EnvTemplate((String) row[0], (String) row[1], (String) row[2],
                ((Number) row[3]).intValue(), (String) row[4], (String) row[5],
                (String) row[6], (String) row[7], ((Number) row[8]).longValue());
    }

    // ---------------------------------------------------------------- 资源

    @Override
    public List<EnvResource> resources(String env) {
        requireEnv(env);
        try {
            return db.read(em -> PlatformDb.rows(em,
                    "SELECT " + RESOURCE_COLUMNS + " FROM env_resources WHERE env = ? ORDER BY kind, name",
                    env).stream().map(this::mapResource).toList());
        } catch (RuntimeException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "中间件资源查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public Optional<EnvResource> resource(String id) {
        try {
            return db.read(em -> PlatformDb.row(em,
                    "SELECT " + RESOURCE_COLUMNS + " FROM env_resources WHERE id = ?", id)
                    .map(this::mapResource));
        } catch (RuntimeException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "中间件资源查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public EnvResource saveResource(EnvResource r) {
        requireEnv(r.env());
        String id = r.id() == null || r.id().isBlank() ? newId("res") : r.id();
        SecretBox.Sealed sealed = r.password() == null || r.password().isEmpty()
                ? null : secretBox.encrypt(r.password());
        long now = System.currentTimeMillis();
        String sql = """
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
                """ + (sealed == null ? ""
                : ", password_enc=excluded.password_enc, password_iv=excluded.password_iv");
        try {
            db.run(em -> PlatformDb.update(em, sql,
                    id,
                    r.env(),
                    r.kind(),
                    r.name() == null || r.name().isBlank() ? r.kind() : r.name(),
                    r.host(),
                    r.port(),
                    r.containerPort(),
                    blankToNull(r.internalHost()),
                    blankToNull(r.databaseName()),
                    blankToNull(r.username()),
                    sealed == null ? null : sealed.cipherB64(),
                    sealed == null ? null : sealed.ivB64(),
                    blankToNull(r.network()),
                    blankToNull(r.serverId()),
                    r.source() == null ? "MANUAL" : r.source(),
                    r.lastCheckAt() > 0 && r.running() ? 1 : 0,
                    r.lastCheckAt(),
                    blankToNull(r.lastError()),
                    now));
        } catch (RuntimeException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "中间件资源保存失败：" + e.getMessage(), e);
        }
        return resource(id).orElseThrow();
    }

    @Override
    public void deleteResource(String id) {
        try {
            db.run(em -> PlatformDb.update(em, "DELETE FROM env_resources WHERE id = ?", id));
        } catch (RuntimeException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "中间件资源删除失败：" + e.getMessage(), e);
        }
    }

    @Override
    public void recordHealth(String id, boolean running, String error) {
        long now = System.currentTimeMillis();
        try {
            db.run(em -> PlatformDb.update(em, """
                    UPDATE env_resources SET running=?, last_check_at=?, last_error=?, updated_at=?
                    WHERE id=?
                    """, running ? 1 : 0, now, blankToNull(error), now, id));
        } catch (RuntimeException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "健康检查结果写入失败：" + e.getMessage(), e);
        }
    }

    private EnvResource mapResource(Object[] row) {
        String enc = (String) row[10];
        String iv = (String) row[11];
        String password = null;
        if (enc != null && iv != null) {
            try {
                password = secretBox.decrypt(enc, iv);
            } catch (RuntimeException e) {
                log.warn("中间件密码解密失败 id={}：{}", row[0], e.getMessage());
            }
        }
        return new EnvResource((String) row[0], (String) row[1], (String) row[2],
                (String) row[3], (String) row[4], ((Number) row[5]).intValue(),
                ((Number) row[6]).intValue(), (String) row[7],
                (String) row[8], (String) row[9], password,
                (String) row[12], (String) row[13], (String) row[14],
                ((Number) row[15]).intValue() == 1, ((Number) row[16]).longValue(),
                (String) row[17]);
    }

    // ---------------------------------------------------------------- 环境变量

    @Override
    public List<EnvVariable> envVars(String env) {
        requireEnv(env);
        Map<String, String> plain = plainEnvVars(env);
        String sql = "SELECT var_key, is_secret, source, updated_at, value_kind, secret_ref "
                + "FROM env_vars WHERE env = ? ORDER BY var_key";
        try {
            return db.read(em -> {
                List<EnvVariable> out = new ArrayList<>();
                for (Object[] row : PlatformDb.rows(em, sql, env)) {
                    String key = (String) row[0];
                    boolean secret = ((Number) row[1]).intValue() == 1;
                    String kind = (String) row[4];
                    String ref = (String) row[5];
                    String value = plain.getOrDefault(key, "");
                    String shown = EnvVariable.KIND_SECRET_REF.equals(kind) && ref != null
                            ? "secret://" + ref
                            : secret ? secretBox.mask(value) : value;
                    out.add(new EnvVariable(null, env, key, secret, shown,
                            row[2] == null ? "TEMPLATE" : (String) row[2],
                            ((Number) row[3]).longValue(), kind, ref));
                }
                return out;
            });
        } catch (RuntimeException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "环境变量查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public EnvVariable saveEnvVar(String env, String key, String value, boolean secret, String source) {
        requireEnv(env);
        requireKey(key);
        long now = System.currentTimeMillis();
        String ref = SecretRefs.whole(value);
        boolean refMode = ref != null;
        boolean keep = value == null || value.isEmpty();
        SecretBox.Sealed sealed = keep ? null : secretBox.encrypt(refMode ? "" : value);
        try {
            db.inTx(em -> {
                if (keep && !exists(em, "env_vars", "env", env, "var_key", key)) {
                    throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_INPUT, "新变量必须提供值：" + key);
                }
                if (keep) {
                    PlatformDb.update(em,
                            "UPDATE env_vars SET is_secret=?, source=?, updated_at=? WHERE env=? AND var_key=?",
                            secret ? 1 : 0, source == null ? "TEMPLATE" : source, now, env, key);
                } else {
                    PlatformDb.update(em, """
                            INSERT INTO env_vars
                                (env, var_key, value_enc, value_iv, is_secret, source, updated_at,
                                 value_kind, secret_ref)
                            VALUES (?,?,?,?,?,?,?,?,?)
                            ON CONFLICT(env, var_key) DO UPDATE SET
                                value_enc=excluded.value_enc, value_iv=excluded.value_iv,
                                is_secret=excluded.is_secret, source=excluded.source,
                                updated_at=excluded.updated_at, value_kind=excluded.value_kind,
                                secret_ref=excluded.secret_ref
                            """,
                            env, key, sealed.cipherB64(), sealed.ivB64(),
                            refMode || secret ? 1 : 0,
                            source == null ? "TEMPLATE" : source,
                            now,
                            refMode ? EnvVariable.KIND_SECRET_REF : EnvVariable.KIND_INLINE,
                            ref);
                }
                return null;
            });
        } catch (EnvironmentException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "环境变量保存失败：" + e.getMessage(), e);
        }
        return envVars(env).stream().filter(v -> v.key().equals(key)).findFirst().orElseThrow();
    }

    @Override
    public Optional<String> envVarValue(String env, String key) {
        requireEnv(env);
        requireKey(key);
        try {
            return db.read(em -> PlatformDb.row(em,
                    "SELECT value_enc, value_iv FROM env_vars WHERE env = ? AND var_key = ?", env, key)
                    .map(row -> secretBox.decrypt((String) row[0], (String) row[1])));
        } catch (RuntimeException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "环境变量读取失败：" + e.getMessage(), e);
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
        try {
            return db.read(em -> {
                Map<String, String> out = new LinkedHashMap<>();
                for (Object[] row : PlatformDb.rows(em,
                        "SELECT var_key, source FROM env_vars WHERE env = ?", env)) {
                    out.put((String) row[0],
                            row[1] == null ? "TEMPLATE" : (String) row[1]);
                }
                return out;
            });
        } catch (RuntimeException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "环境变量来源查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public void deleteEnvVar(String env, String key) {
        requireEnv(env);
        requireKey(key);
        try {
            db.run(em -> PlatformDb.update(em,
                    "DELETE FROM env_vars WHERE env=? AND var_key=?", env, key));
        } catch (RuntimeException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "环境变量删除失败：" + e.getMessage(), e);
        }
    }

    private Map<String, String> plainEnvVars(String env) {
        return plainMap("SELECT var_key, value_enc, value_iv FROM env_vars WHERE env = ?", env);
    }

    private Map<String, String> plainOverrides(String projectId, String env) {
        try {
            return db.read(em -> {
                Map<String, String> out = new LinkedHashMap<>();
                for (Object[] row : PlatformDb.rows(em,
                        "SELECT var_key, value_enc, value_iv FROM project_env_overrides "
                                + "WHERE project_id = ? AND env = ? ORDER BY var_key",
                        projectId, env)) {
                    out.put((String) row[0], secretBox.decrypt((String) row[1], (String) row[2]));
                }
                return out;
            });
        } catch (RuntimeException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "项目覆盖查询失败：" + e.getMessage(), e);
        }
    }

    private Map<String, String> plainMap(String sql, String env) {
        try {
            return db.read(em -> {
                Map<String, String> out = new LinkedHashMap<>();
                for (Object[] row : PlatformDb.rows(em, sql, env)) {
                    out.put((String) row[0], secretBox.decrypt((String) row[1], (String) row[2]));
                }
                return out;
            });
        } catch (RuntimeException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "环境变量读取失败：" + e.getMessage(), e);
        }
    }

    // ---------------------------------------------------------------- 项目覆盖

    @Override
    public List<EnvVariable> overrides(String projectId, String env) {
        requireProject(projectId);
        requireEnv(env);
        Map<String, String> plain = plainOverrides(projectId, env);
        String sql = "SELECT var_key, is_secret, updated_at, value_kind, secret_ref "
                + "FROM project_env_overrides WHERE project_id=? AND env=? ORDER BY var_key";
        try {
            return db.read(em -> {
                List<EnvVariable> out = new ArrayList<>();
                for (Object[] row : PlatformDb.rows(em, sql, projectId, env)) {
                    String key = (String) row[0];
                    boolean secret = ((Number) row[1]).intValue() == 1;
                    String kind = (String) row[3];
                    String ref = (String) row[4];
                    String value = plain.getOrDefault(key, "");
                    String shown = EnvVariable.KIND_SECRET_REF.equals(kind) && ref != null
                            ? "secret://" + ref
                            : secret ? secretBox.mask(value) : value;
                    out.add(new EnvVariable(projectId, env, key, secret, shown, "OVERRIDE",
                            ((Number) row[2]).longValue(), kind, ref));
                }
                return out;
            });
        } catch (RuntimeException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "项目覆盖查询失败：" + e.getMessage(), e);
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
        boolean keep = value == null || value.isEmpty();
        SecretBox.Sealed sealed = keep ? null : secretBox.encrypt(refMode ? "" : value);
        try {
            db.inTx(em -> {
                if (keep && !overrideExists(em, projectId, env, key)) {
                    throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_INPUT, "新覆盖项必须提供值：" + key);
                }
                if (keep) {
                    PlatformDb.update(em, """
                            UPDATE project_env_overrides SET is_secret=?, updated_at=?
                            WHERE project_id=? AND env=? AND var_key=?
                            """, secret ? 1 : 0, now, projectId, env, key);
                } else {
                    PlatformDb.update(em, """
                            INSERT INTO project_env_overrides
                                (project_id, env, var_key, value_enc, value_iv, is_secret, updated_at,
                                 value_kind, secret_ref)
                            VALUES (?,?,?,?,?,?,?,?,?)
                            ON CONFLICT(project_id, env, var_key) DO UPDATE SET
                                value_enc=excluded.value_enc, value_iv=excluded.value_iv,
                                is_secret=excluded.is_secret, updated_at=excluded.updated_at,
                                value_kind=excluded.value_kind, secret_ref=excluded.secret_ref
                            """,
                            projectId, env, key, sealed.cipherB64(), sealed.ivB64(),
                            refMode || secret ? 1 : 0, now,
                            refMode ? EnvVariable.KIND_SECRET_REF : EnvVariable.KIND_INLINE,
                            ref);
                }
                return null;
            });
        } catch (EnvironmentException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "项目覆盖保存失败：" + e.getMessage(), e);
        }
        return overrides(projectId, env).stream()
                .filter(v -> v.key().equals(key)).findFirst().orElseThrow();
    }

    @Override
    public void deleteOverride(String projectId, String env, String key) {
        requireProject(projectId);
        requireEnv(env);
        requireKey(key);
        try {
            db.run(em -> PlatformDb.update(em,
                    "DELETE FROM project_env_overrides WHERE project_id=? AND env=? AND var_key=?",
                    projectId, env, key));
        } catch (RuntimeException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "项目覆盖删除失败：" + e.getMessage(), e);
        }
    }

    // ---------------------------------------------------------------- 密钥引用

    @Override
    public List<SecretEntry> secrets(String projectId, String env) {
        requireProject(projectId);
        requireEnv(env);
        String sql = "SELECT project_id, env, name, version, rotated_at, created_at "
                + "FROM secrets WHERE project_id=? AND env=? ORDER BY name";
        try {
            return db.read(em -> {
                List<SecretEntry> out = new ArrayList<>();
                for (Object[] row : PlatformDb.rows(em, sql, projectId, env)) {
                    long updated = Math.max(((Number) row[4]).longValue(),
                            ((Number) row[5]).longValue());
                    out.add(new SecretEntry((String) row[0], (String) row[1],
                            (String) row[2], ((Number) row[3]).intValue(), updated));
                }
                return out;
            });
        } catch (RuntimeException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "密钥查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public SecretEntry saveSecret(String projectId, String env, String name, String value) {
        requireProject(projectId);
        requireEnv(env);
        requireSecretName(name);
        if (value == null || value.isEmpty()) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_INPUT, "密钥值不能为空：" + name);
        }
        long now = System.currentTimeMillis();
        SecretBox.Sealed sealed = secretBox.encrypt(value);
        try {
            db.inTx(em -> {
                // 实体未声明 (project_id, env, name) 唯一约束，先取现有 id 再按主键 upsert，
                // 语义与原 UNIQUE 冲突一致：保留 created_at，版本 +1。
                String id = PlatformDb.first(em,
                                "SELECT id FROM secrets WHERE project_id=? AND env=? AND name=?",
                                projectId, env, name)
                        .map(Object::toString)
                        .orElseGet(() -> java.util.UUID.randomUUID().toString());
                PlatformDb.update(em, """
                        INSERT INTO secrets
                            (id, project_id, env, name, value_enc, value_iv, version, created_at, rotated_at)
                        VALUES (?,?,?,?,?,?,1,?,0)
                        ON CONFLICT(id) DO UPDATE SET
                            value_enc=excluded.value_enc, value_iv=excluded.value_iv,
                            version=secrets.version + 1, rotated_at=excluded.created_at
                        """, id, projectId, env, name, sealed.cipherB64(), sealed.ivB64(), now);
                return null;
            });
        } catch (RuntimeException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "密钥保存失败：" + e.getMessage(), e);
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
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_INPUT, "密钥不存在，无法轮换：" + name);
        }
        return saveSecret(projectId, env, name, value);
    }

    @Override
    public void deleteSecret(String projectId, String env, String name) {
        requireProject(projectId);
        requireEnv(env);
        requireSecretName(name);
        try {
            db.run(em -> PlatformDb.update(em,
                    "DELETE FROM secrets WHERE project_id=? AND env=? AND name=?",
                    projectId, env, name));
        } catch (RuntimeException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "密钥删除失败：" + e.getMessage(), e);
        }
    }

    @Override
    public Optional<String> secretValue(String projectId, String env, String name) {
        requireProject(projectId);
        requireEnv(env);
        requireSecretName(name);
        try {
            return db.read(em -> PlatformDb.row(em,
                    "SELECT value_enc, value_iv FROM secrets WHERE project_id=? AND env=? AND name=?",
                    projectId, env, name)
                    .map(row -> secretBox.decrypt((String) row[0], (String) row[1])));
        } catch (RuntimeException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "密钥读取失败：" + e.getMessage(), e);
        }
    }

    private int secretVersion(String projectId, String env, String name) {
        try {
            return db.read(em -> PlatformDb.first(em,
                            "SELECT version FROM secrets WHERE project_id=? AND env=? AND name=?",
                            projectId, env, name)
                    .map(v -> ((Number) v).intValue())
                    .orElse(0));
        } catch (RuntimeException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "密钥版本读取失败：" + e.getMessage(), e);
        }
    }

    private void requireSecretName(String name) {
        if (!SecretRefs.validName(name)) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_INPUT, "密钥引用名只允许字母、数字、点、下划线与短横线：" + name);
        }
    }

    private record StoredValue(String value, String kind, String ref) {
    }

    private Map<String, StoredValue> storedEnvVars(String env) {
        return storedValues("SELECT var_key, value_enc, value_iv, value_kind, secret_ref "
                + "FROM env_vars WHERE env = ?", env);
    }

    private Map<String, StoredValue> storedOverrides(String projectId, String env) {
        try {
            return db.read(em -> {
                Map<String, StoredValue> out = new LinkedHashMap<>();
                for (Object[] row : PlatformDb.rows(em,
                        "SELECT var_key, value_enc, value_iv, value_kind, secret_ref "
                                + "FROM project_env_overrides WHERE project_id=? AND env=? "
                                + "ORDER BY var_key", projectId, env)) {
                    String kind = (String) row[3];
                    String ref = (String) row[4];
                    String value = EnvVariable.KIND_SECRET_REF.equals(kind)
                            ? "" : secretBox.decrypt((String) row[1], (String) row[2]);
                    out.put((String) row[0], new StoredValue(value, kind, ref));
                }
                return out;
            });
        } catch (RuntimeException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "项目覆盖查询失败：" + e.getMessage(), e);
        }
    }

    private Map<String, StoredValue> storedValues(String sql, String env) {
        try {
            return db.read(em -> {
                Map<String, StoredValue> out = new LinkedHashMap<>();
                for (Object[] row : PlatformDb.rows(em, sql, env)) {
                    String kind = (String) row[3];
                    String ref = (String) row[4];
                    String value = EnvVariable.KIND_SECRET_REF.equals(kind)
                            ? "" : secretBox.decrypt((String) row[1], (String) row[2]);
                    out.put((String) row[0], new StoredValue(value, kind, ref));
                }
                return out;
            });
        } catch (RuntimeException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "环境变量读取失败：" + e.getMessage(), e);
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
        try {
            return db.read(em -> PlatformDb.first(em,
                            "SELECT is_secret FROM project_env_overrides "
                                    + "WHERE project_id=? AND env=? AND var_key=?",
                            projectId, env, key)
                    .map(v -> ((Number) v).intValue() == 1)
                    .orElse(false));
        } catch (RuntimeException e) {
            return true;
        }
    }

    private boolean flag(String sql, String... args) {
        try {
            return db.read(em -> PlatformDb.first(em, sql, (Object[]) args)
                    .map(v -> ((Number) v).intValue() == 1)
                    .orElse(false));
        } catch (RuntimeException e) {
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
        try {
            db.run(em -> PlatformDb.update(em, """
                    INSERT INTO env_bindings
                        (id, project_id, env, mapping_json, mapping_hash, created_by,
                         confirmed_by, confirmed_at, expires_at, status, created_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?)
                    ON CONFLICT(id) DO UPDATE SET
                        mapping_json=excluded.mapping_json, mapping_hash=excluded.mapping_hash,
                        status=excluded.status, confirmed_by=excluded.confirmed_by,
                        confirmed_at=excluded.confirmed_at, expires_at=excluded.expires_at
                    """,
                    id,
                    b.projectId(),
                    b.env(),
                    b.mappingJson(),
                    b.mappingHash(),
                    b.createdBy(),
                    b.confirmedBy(),
                    b.confirmedAt(),
                    b.expiresAt(),
                    b.status() == null ? "DRAFT" : b.status(),
                    now));
        } catch (RuntimeException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "环境绑定保存失败：" + e.getMessage(), e);
        }
        return binding(id).orElseThrow();
    }

    @Override
    public Optional<EnvBinding> binding(String id) {
        try {
            return db.read(em -> PlatformDb.row(em,
                    "SELECT " + BINDING_COLUMNS + " FROM env_bindings WHERE id = ?", id)
                    .map(DbEnvironmentStore::mapBinding));
        } catch (RuntimeException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "环境绑定查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public Optional<EnvBinding> latestConfirmedBinding(String projectId, String env) {
        try {
            return db.read(em -> PlatformDb.row(em,
                    "SELECT " + BINDING_COLUMNS + " FROM env_bindings "
                            + "WHERE project_id = ? AND env = ? "
                            + "AND status = 'CONFIRMED' AND expires_at > ? "
                            + "ORDER BY confirmed_at DESC, created_at DESC LIMIT 1",
                    projectId, env, System.currentTimeMillis())
                    .map(DbEnvironmentStore::mapBinding));
        } catch (RuntimeException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "环境绑定查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public List<EnvBinding> bindings(String projectId, String env, int limit) {
        int size = limit <= 0 ? DEFAULT_BINDING_HISTORY : Math.min(limit, MAX_BINDING_HISTORY);
        try {
            return db.read(em -> PlatformDb.rows(em,
                    "SELECT " + BINDING_COLUMNS + " FROM env_bindings "
                            + "WHERE project_id = ? AND env = ? "
                            // 已解绑的记录也返回：卡片要展示「谁在什么时候解绑的」
                            + "ORDER BY created_at DESC LIMIT ?",
                    projectId, env, size).stream()
                    .map(DbEnvironmentStore::mapBinding).toList());
        } catch (RuntimeException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "环境绑定历史查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public Optional<EnvBinding> revokeBinding(String id, String actor) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        try {
            db.run(em -> PlatformDb.update(em, """
                    UPDATE env_bindings
                    SET status='REVOKED', confirmed_by=?, expires_at=?
                    WHERE id=?
                    """, actor == null || actor.isBlank() ? "user" : actor,
                    System.currentTimeMillis(), id));
        } catch (RuntimeException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "环境绑定解绑失败：" + e.getMessage(), e);
        }
        // 不删除：解绑后记录留在历史里，卡片与审计都能看到
        return binding(id);
    }

    @Override
    public void confirmBinding(String id, String confirmedBy, long expiresAt) {
        try {
            db.run(em -> PlatformDb.update(em, """
                    UPDATE env_bindings
                    SET status='CONFIRMED', confirmed_by=?, confirmed_at=?, expires_at=?
                    WHERE id=?
                    """, confirmedBy, System.currentTimeMillis(), expiresAt, id));
        } catch (RuntimeException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "环境绑定确认失败：" + e.getMessage(), e);
        }
    }

    private static EnvBinding mapBinding(Object[] row) {
        return new EnvBinding((String) row[0], (String) row[1], (String) row[2],
                (String) row[3], (String) row[4], (String) row[5], (String) row[6],
                ((Number) row[7]).longValue(), ((Number) row[8]).longValue(), (String) row[9]);
    }

    // ---------------------------------------------------------------- 端口租约

    @Override
    public Optional<PortLease> lease(String projectId, String env, int containerPort) {
        requireProject(projectId);
        requireEnv(env);
        try {
            return db.read(em -> PlatformDb.row(em,
                    "SELECT project_id, env, container_port, host_port, run_id, updated_at "
                            + "FROM deploy_port_leases "
                            + "WHERE project_id=? AND env=? AND container_port=?",
                    projectId, env, containerPort)
                    .map(row -> new PortLease(projectId, env, containerPort,
                            ((Number) row[3]).intValue(), (String) row[4],
                            ((Number) row[5]).longValue())));
        } catch (RuntimeException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "端口租约查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public List<PortLease> leases(String projectId, String env) {
        requireProject(projectId);
        requireEnv(env);
        try {
            return db.read(em -> {
                List<PortLease> out = new ArrayList<>();
                for (Object[] row : PlatformDb.rows(em,
                        "SELECT project_id, env, container_port, host_port, run_id, updated_at "
                                + "FROM deploy_port_leases "
                                + "WHERE project_id=? AND env=? ORDER BY container_port",
                        projectId, env)) {
                    out.add(new PortLease(projectId, env, ((Number) row[2]).intValue(),
                            ((Number) row[3]).intValue(), (String) row[4],
                            ((Number) row[5]).longValue()));
                }
                return out;
            });
        } catch (RuntimeException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "端口租约查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public void saveLease(PortLease lease) {
        requireProject(lease.projectId());
        requireEnv(lease.env());
        try {
            db.run(em -> PlatformDb.update(em, """
                    INSERT INTO deploy_port_leases
                        (project_id, env, container_port, host_port, run_id, updated_at)
                    VALUES (?,?,?,?,?,?)
                    ON CONFLICT(project_id, env, container_port) DO UPDATE SET
                        host_port=excluded.host_port, run_id=excluded.run_id,
                        updated_at=excluded.updated_at
                    """,
                    lease.projectId(), lease.env(), lease.containerPort(),
                    lease.hostPort(), lease.runId(), System.currentTimeMillis()));
        } catch (RuntimeException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "端口租约保存失败：" + e.getMessage(), e);
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
        try {
            return db.read(em -> {
                List<ServiceLease> out = new ArrayList<>();
                for (Object[] row : PlatformDb.rows(em, sql, (Object[]) args)) {
                    out.add(new ServiceLease((String) row[0], (String) row[1],
                            (String) row[2], ((Number) row[3]).intValue(),
                            (String) row[4], ((Number) row[5]).longValue(),
                            ((Number) row[6]).longValue()));
                }
                return out;
            });
        } catch (RuntimeException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "本地服务租约查询失败：" + e.getMessage(), e);
        }
    }

    @Override
    public void saveServiceLease(ServiceLease lease) {
        requireProject(lease.projectId());
        requireEnv(lease.env());
        requireService(lease.service());
        try {
            db.run(em -> PlatformDb.update(em, """
                    INSERT INTO project_service_leases
                        (project_id, env, service, host_port, status, pid, updated_at)
                    VALUES (?,?,?,?,?,?,?)
                    ON CONFLICT(project_id, env, service) DO UPDATE SET
                        host_port=excluded.host_port, status=excluded.status,
                        pid=excluded.pid, updated_at=excluded.updated_at
                    """,
                    lease.projectId(), lease.env(), lease.service(), lease.hostPort(),
                    lease.status() == null ? "STOPPED" : lease.status(),
                    lease.pid(), System.currentTimeMillis()));
        } catch (RuntimeException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "本地服务租约保存失败：" + e.getMessage(), e);
        }
    }

    @Override
    public void deleteServiceLease(String projectId, String env, String service) {
        requireProject(projectId);
        requireEnv(env);
        requireService(service);
        try {
            db.run(em -> PlatformDb.update(em,
                    "DELETE FROM project_service_leases WHERE project_id=? AND env=? AND service=?",
                    projectId, env, service));
        } catch (RuntimeException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE, "本地服务租约删除失败：" + e.getMessage(), e);
        }
    }

    // ---------------------------------------------------------------- 工具

    private static boolean exists(EntityManager em, String table, String col1, String val1,
                                  String col2, String val2) {
        return PlatformDb.first(em,
                "SELECT 1 FROM " + table + " WHERE " + col1 + "=? AND " + col2 + "=?",
                val1, val2).isPresent();
    }

    private static boolean overrideExists(EntityManager em, String projectId, String env, String key) {
        return PlatformDb.first(em,
                "SELECT 1 FROM project_env_overrides WHERE project_id=? AND env=? AND var_key=?",
                projectId, env, key).isPresent();
    }

    private static String newId(String prefix) {
        return prefix + "-" + java.util.UUID.randomUUID().toString().substring(0, 8);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static void requireProject(String projectId) {
        if (projectId == null || !projectId.matches("[A-Za-z0-9_-]{1,32}")) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_INPUT, "非法项目 ID：" + projectId);
        }
    }

    private static void requireEnv(String env) {
        EnvNames.requireExact(env);
    }

    private static void requireKey(String key) {
        if (key == null || !key.matches("[A-Za-z_][A-Za-z0-9_]{0,63}")) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_INPUT, "变量名不合法（需 [A-Za-z_][A-Za-z0-9_]*）：" + key);
        }
    }

    private static void requireService(String service) {
        if (!List.of(ServiceLease.BACKEND, ServiceLease.FRONTEND).contains(service)) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_INPUT, "服务只能是 backend / frontend：" + service);
        }
    }
}
