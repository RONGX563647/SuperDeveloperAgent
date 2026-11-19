package top.rongx.agent.gateway.settings;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import top.rongx.agent.domain.error.ErrorCode;
import top.rongx.agent.domain.error.EnvironmentException;
import top.rongx.agent.infra.persistence.SqliteToTargetCopier;
import top.rongx.agent.infra.persistence.config.AgentDbProperties;

/**
 * 一键迁移：把当前 SQLite 库的数据搬进一个 PostgreSQL 库。
 *
 * <p>放在网关侧而不是 infra：Flyway 的依赖与迁移脚本的 classpath 位置（{@code db/migration}）
 * 都在网关，infra 只提供与后端无关的行复制器 {@link SqliteToTargetCopier}。
 *
 * <p>三步走，任何一步失败都明确报出原因（设置页直接展示）：
 * <ol>
 *   <li><b>连通性</b>：建一条 PG 连接，连不上立即失败（不产生半成品）；</li>
 *   <li><b>建 schema</b>：对该 PG 库跑 Flyway（与主库同一套脚本），表/索引与当前版本一致；</li>
 *   <li><b>搬数据</b>：逐表 {@code ON CONFLICT DO NOTHING} 复制，可重复执行、不覆盖 PG 已有行。</li>
 * </ol>
 *
 * <p>迁移不停写 SQLite：复制只读源、幂等写目标，业务可继续跑；真正切换后端由
 * {@code DbBackendConfig} 落盘 + 重启完成，两件事解耦。
 */
@Component
public class SqliteToPostgresMigrator {

    private static final Logger log = LoggerFactory.getLogger(SqliteToPostgresMigrator.class);

    /** 迁移结果：各阶段行数与耗时，供设置页逐表展示。 */
    public record Result(boolean ok, String message, String source, String target,
                         int tables, int copiedRows, int skippedTables,
                         List<Map<String, Object>> details, long elapsedMs) {
    }

    /**
     * 执行迁移。
     *
     * @param props    当前配置（用于定位 SQLite 源文件）
     * @param url      目标 PG JDBC URL
     * @param username 目标库用户名
     * @param password 目标库密码（明文，只在内存）
     */
    public Result migrate(AgentDbProperties props, String url, String username, String password) {
        long start = System.currentTimeMillis();
        String source = props.jdbcUrl();
        if (!source.startsWith("jdbc:sqlite:")) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE,
                    "当前后端不是 SQLite，无可迁移的源数据（当前：" + source + "）");
        }
        if (url == null || !url.startsWith("jdbc:postgresql:")) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_INPUT,
                    "目标必须是 jdbc:postgresql: 开头的地址");
        }
        createSchema(url, username, password);
        SqliteToTargetCopier.Report report = copyRows(source, url, username, password);
        long elapsed = System.currentTimeMillis() - start;
        String message = report.complete()
                ? "迁移完成：" + report.copied() + " 行"
                : "部分完成：" + report.copied() + " 行，" + report.skippedTables() + " 张表被跳过";
        log.info("SQLite → PostgreSQL 迁移{}：源 {}，目标 {}，{} 行 / {} 表，耗时 {}ms",
                report.complete() ? "完成" : "部分完成", source, url,
                report.copied(), report.tables().size(), elapsed);
        return new Result(report.complete(), message, source, url, report.tables().size(),
                report.copied(), report.skippedTables(), details(report), elapsed);
    }

    /** 用同一套迁移脚本把目标 PG 库建到当前版本。 */
    private void createSchema(String url, String username, String password) {
        try {
            Flyway.configure()
                    .dataSource(url, username, password)
                    .locations("classpath:db/migration/common", "classpath:db/migration/postgresql")
                    .baselineOnMigrate(true)
                    .baselineVersion("1")
                    .load()
                    .migrate();
        } catch (RuntimeException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE,
                    "目标库建表失败（检查账号是否有 DDL 权限）：" + rootMessage(e), e);
        }
    }

    /** 源 SQLite（只读）+ 目标 PG（写）逐表复制。 */
    private SqliteToTargetCopier.Report copyRows(String sourceUrl, String targetUrl,
                                                 String username, String password) {
        Properties readOnly = new Properties();
        readOnly.setProperty("open_mode", "1");
        try (Connection source = DriverManager.getConnection(sourceUrl, readOnly);
             Connection target = DriverManager.getConnection(targetUrl, username, password)) {
            source.setReadOnly(true);
            return SqliteToTargetCopier.copy(source, target);
        } catch (SQLException e) {
            throw new EnvironmentException(ErrorCode.ENVIRONMENT_INVALID_STATE,
                    "迁移失败：" + rootMessage(e), e);
        }
    }

    private static List<Map<String, Object>> details(SqliteToTargetCopier.Report report) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (SqliteToTargetCopier.TableResult table : report.tables()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("table", table.table());
            row.put("copied", table.copied());
            row.put("sourceRows", table.sourceRows());
            row.put("ok", table.ok());
            row.put("skipped", table.skipped());
            rows.add(row);
        }
        return rows;
    }

    private static String rootMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        if (message == null || message.isBlank()) {
            return cause.getClass().getSimpleName();
        }
        return message.length() > 300 ? message.substring(0, 300) + "…" : message;
    }
}
