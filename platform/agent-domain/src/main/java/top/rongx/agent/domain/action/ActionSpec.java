package top.rongx.agent.domain.action;

import java.time.Duration;
import java.util.List;

/**
 * 解析后的动作规格（确定性、可复现、可审计）。
 *
 * <p>注意 {@code steps} 是**多条 argv**，而不是一条 shell 字符串：由人写在仓库里，
 * 模型只能选动作、不能写命令（见《CI/CD 方案替代与自创》§4.4）。
 *
 * @param action  动作
 * @param steps   按顺序执行的命令（每条是 argv 数组）
 * @param produce 产物的 glob（相对工作区），例如 {@code target/*.jar}
 * @param timeout 整个动作的超时
 * @param source  来源（用于证据链）
 */
public record ActionSpec(
        ActionId action,
        List<List<String>> steps,
        List<String> produce,
        Duration timeout,
        ActionSource source) {

    public ActionSpec {
        if (steps == null || steps.isEmpty()) {
            throw new IllegalArgumentException("动作 " + action + " 没有可执行命令");
        }
        steps = steps.stream().map(List::copyOf).toList();
        produce = produce == null ? List.of() : List.copyOf(produce);
        timeout = timeout == null ? Duration.ofMinutes(15) : timeout;
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("超时必须为正");
        }
        if (source == null) {
            throw new IllegalArgumentException("来源不能为空（审计需要）");
        }
    }

    /** 人类可读的一句话，用于确认卡片与日志。 */
    public String describe() {
        return action.wireName() + " ← " + source.ref() + "："
                + steps.stream().map(s -> String.join(" ", s)).reduce((a, b) -> a + " && " + b).orElse("")
                + "（timeout " + timeout.toMinutes() + "m）";
    }
}
