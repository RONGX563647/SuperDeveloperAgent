package top.rongx.agent.infra.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.rongx.agent.domain.settings.TokenUsageRepository.UsageTotals;

/**
 * 真实会话缓存命中率读数（token 爆炸专项 6.4 第 2 条：cached/input ≥ 60%）。
 *
 * <p>验收缺的不是采集口径——{@link UsageTotals#cacheHitRate()} 与
 * {@code /api/v1/usage/summary} 早已上线——而是<b>拿一次真实读数把它固化成证据</b>。
 * 本测试只读打开真实 {@code token_usage} 表（{@code mode=ro}，绝不建表/写入），
 * 复用生产同一条命中率公式，断言达到验收门槛，并把读数打到 stdout 供 CI 留痕。
 *
 * <p><b>无数据时不自造数字</b>：库不存在或表为空时跳过并提示如何采集——
 * 这正是验收「没有任何一次真实会话命中率读数」时该有的诚实行为。
 *
 * <p>库路径可用 {@code -Dtoken.baseline.db=<path>} 覆盖（PG 环境可指向导出的同名表快照）；
 * 默认取平台本地统一库 {@code data/workspaces/_platform/platform.db}（相对 platform 模块）。
 */
class TokenHitRateBaselineTest {

    /** 验收 6.4 第 2 条硬门槛：缓存命中率 ≥ 60%。 */
    private static final int MIN_HIT_RATE = 60;

    @Test
    @DisplayName("真实 token_usage 命中率读数 ≥ 60%（复用生产口径，无数据则跳过）")
    void realCacheHitRateMeetsBaseline() throws Exception {
        String override = System.getProperty("token.baseline.db");
        Path db;
        if (override != null && !override.isBlank()) {
            db = Path.of(override).toAbsolutePath().normalize();
            assumeTrue(Files.isRegularFile(db),
                    "token.baseline.db 指向的库不存在：" + db);
        } else {
            // surefire 的 cwd 是 agent-infra 模块根，向上两级才是仓库根；兼容从 platform 根直接跑。
            Path base = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
            Path rel = Path.of("data", "workspaces", "_platform", "platform.db");
            // 只认非空库：测试/轻量上下文会在 platform/data 下留一个 0 字节空壳，不能拿它当真实数据。
            db = java.util.stream.Stream.of(
                            base.resolve("..").resolve("..").resolve(rel),
                            base.resolve("..").resolve(rel),
                            base.resolve(rel))
                    .map(Path::normalize)
                    .filter(p -> Files.isRegularFile(p) && !isEmptyFile(p))
                    .findFirst()
                    .orElseGet(() -> base.resolve("..").resolve("..").resolve(rel).normalize());
            assumeTrue(Files.isRegularFile(db),
                    "真实用量库不存在：" + db + "；先跑一轮带模型的会话，或用 -Dtoken.baseline.db 指向已有库");
        }

        long calls;
        long input;
        long cached;
        long output;
        long total;
        long lastAt;
        String url = "jdbc:sqlite:file:" + db.toString().replace('\\', '/') + "?mode=ro";
        try (Connection conn = DriverManager.getConnection(url);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("""
                     SELECT COUNT(*),
                            COALESCE(SUM(input_tokens), 0),
                            COALESCE(SUM(cached_tokens), 0),
                            COALESCE(SUM(output_tokens), 0),
                            COALESCE(SUM(total_tokens), 0),
                            COALESCE(MAX(created_at), 0)
                     FROM token_usage
                     """)) {
            assumeTrue(rs.next(), "token_usage 聚合无结果：" + db);
            calls = rs.getLong(1);
            input = rs.getLong(2);
            cached = rs.getLong(3);
            output = rs.getLong(4);
            total = rs.getLong(5);
            lastAt = rs.getLong(6);
        } catch (java.sql.SQLException noTable) {
            assumeTrue(false, "库里没有 token_usage 表：" + db + "（" + noTable.getMessage()
                    + "）；先跑一轮带模型的会话采集，不自造读数");
            return; // 不可达：assumeTrue 抛 TestAbortedException
        }

        assumeTrue(calls > 0 && input > 0,
                "token_usage 无真实用量（calls=" + calls + ", input=" + input
                        + "）；先采集一轮真实会话，不自造读数");

        // 与生产、前端、API 完全同一条口径：读生产 record 的派生方法，不在这里另写公式。
        UsageTotals totals = new UsageTotals(calls, input, cached, output, total, lastAt);
        int hitRate = totals.cacheHitRate();
        assertEquals(totals.cacheHitRate(), hitRate, "读数必须走生产派生口径");
        System.out.printf("[TOKEN-BASELINE] 真实读数 db=%s calls=%d input=%d cached=%d "
                        + "output=%d total=%d hitRate=%d%% lastAt=%d%n",
                db, calls, input, cached, output, total, hitRate, lastAt);

        assertTrue(hitRate >= MIN_HIT_RATE,
                String.format("真实缓存命中率 %d%% 低于验收门槛 %d%%（cached=%d/input=%d）",
                        hitRate, MIN_HIT_RATE, cached, input));
    }

    private static boolean isEmptyFile(Path p) {
        try {
            return Files.size(p) <= 0;
        } catch (Exception e) {
            return true;
        }
    }
}
