package top.rongx.agent.sample;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 正例样例：全部写法合规，用来验证规则不会误报。
 *
 * <p>故意覆盖与反例同类的场景（时间、金额、SQL、进程、日志），
 * 只要规则"写宽了"就会在这里红。
 */
class CleanSample {

    private static final System.Logger LOG = System.getLogger(CleanSample.class.getName());

    private final BigDecimal feeAmount;

    CleanSample(BigDecimal feeAmount) {
        this.feeAmount = feeAmount;
    }

    Instant now() {
        return Instant.now();
    }

    String sql() {
        return "SELECT id, name FROM users WHERE id = ?";
    }

    List<String> command() {
        return List.of("docker", "ps");
    }

    void logUsage() {
        LOG.log(System.Logger.Level.INFO, "fee={0}", feeAmount);
    }

    boolean deletedAtIsSet() {
        return deletedAt != null;
    }
}
