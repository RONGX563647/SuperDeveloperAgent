package top.rongx.agent.infra.persistence.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 数据库配置：{@code agent.db.url} 留空走 SQLite，配置 PostgreSQL URL 走 PG。
 *
 * <p>只认“未配置”时回落 SQLite；配置了 PG 但连不上必须启动失败，
 * 否则会出现部分数据落 PG、部分落本地文件的不可合并状态。
 */
@ConfigurationProperties(prefix = "agent.db")
public record AgentDbProperties(
        String url,
        String username,
        String password,
        String sqliteFile,
        Integer poolSize,
        Boolean importLegacy) {

    private static final String SQLITE_PREFIX = "jdbc:sqlite:";
    private static final String POSTGRES_PREFIX = "jdbc:postgresql:";
    private static final String DEFAULT_SQLITE_FILE = "./data/workspaces/_platform/platform.db";

    public AgentDbProperties {
        url = url == null ? "" : url.trim();
        username = username == null ? "" : username;
        password = password == null ? "" : password;
        sqliteFile = sqliteFile == null || sqliteFile.isBlank()
                ? DEFAULT_SQLITE_FILE : sqliteFile.trim();
        poolSize = poolSize == null || poolSize < 1 ? 8 : poolSize;
        importLegacy = importLegacy != null && importLegacy;
        if (!url.isEmpty()
                && !url.startsWith(SQLITE_PREFIX)
                && !url.startsWith(POSTGRES_PREFIX)) {
            throw new IllegalArgumentException(
                    "agent.db.url 只支持 jdbc:sqlite: 或 jdbc:postgresql: 前缀，当前值不是合法 JDBC URL");
        }
    }

    public DbBackend backend() {
        return url.startsWith(POSTGRES_PREFIX) ? DbBackend.POSTGRES : DbBackend.SQLITE;
    }

    public boolean isPostgres() {
        return backend() == DbBackend.POSTGRES;
    }

    /** SQLite 模式下 url 留空，用本地文件；显式 sqlite URL 优先。 */
    public String jdbcUrl() {
        if (isPostgres()) {
            return url;
        }
        return url.isEmpty() ? SQLITE_PREFIX + sqliteFile : url;
    }
}
