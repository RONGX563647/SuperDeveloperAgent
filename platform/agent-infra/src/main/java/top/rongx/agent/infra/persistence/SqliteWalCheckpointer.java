package top.rongx.agent.infra.persistence;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import top.rongx.agent.infra.persistence.config.AgentDbProperties;

/**
 * 启动时把统一 SQLite 库的 WAL 合并回主文件。
 *
 * <p>根因：HikariCP 常驻多个连接，SQLite 只有在最后一个连接关闭时才自动 checkpoint，
 * 因此数据会长期停留在 {@code .db-wal} 里，主 {@code .db} 文件仍是旧的甚至只有一页。
 * 此时若备份只拷 {@code .db}、或有人清理 {@code -wal/-shm}，未合并的数据就永久丢失。
 *
 * <p>本组件在应用启动、{@code LegacySqliteImporter} 导入完成之后执行 TRUNCATE checkpoint，
 * 让主文件自包含；PostgreSQL 模式直接跳过。checkpoint 失败只告警不阻断启动。
 */
public final class SqliteWalCheckpointer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SqliteWalCheckpointer.class);

    private final PlatformDb db;
    private final AgentDbProperties props;

    public SqliteWalCheckpointer(PlatformDb db, AgentDbProperties props) {
        this.db = db;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (props.isPostgres()) {
            return;
        }
        try {
            Checkpoint result = checkpoint(db.dataSource());
            if (result.busy() != 0) {
                log.warn("WAL checkpoint 遇到并发连接（busy={}），数据仍完整保留在 WAL 中；"
                        + "备份时须连同 -wal/-shm 一起复制", result.busy());
            } else {
                log.info("WAL checkpoint 完成：合并 {} 页，日志 {} 页",
                        result.checkpointed(), result.logged());
            }
        } catch (SQLException e) {
            log.warn("WAL checkpoint 失败（不影响启动）: {}", e.getMessage());
        }
    }

    /**
     * 在给定数据源上执行 {@code PRAGMA wal_checkpoint(TRUNCATE)}。
     *
     * <p>返回三列 {@code (busy, log, checkpointed)}：busy=0 表示成功，其余表示有连接占用；
     * 非 WAL 库返回 busy=-1。静态方法便于单元测试直接构造数据源调用。
     */
    public static Checkpoint checkpoint(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("PRAGMA wal_checkpoint(TRUNCATE)")) {
            if (rs.next()) {
                return new Checkpoint(rs.getInt(1), rs.getInt(2), rs.getInt(3));
            }
            return new Checkpoint(-1, 0, 0);
        }
    }

    /** checkpoint 结果：是否忙、WAL 日志页数、已合并页数。 */
    public record Checkpoint(int busy, int logged, int checkpointed) {
        public boolean ok() {
            return busy == 0;
        }
    }
}
