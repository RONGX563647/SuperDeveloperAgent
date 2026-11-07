package top.rongx.agent.domain.action;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 解析后的动作规格（确定性、可复现、可审计）。
 *
 * <p>注意 {@code steps} 是**多条 argv**，而不是一条 shell 字符串：由人写在仓库里，
 * 模型只能选动作、不能写命令（见《CI/CD 方案替代与自创》§4.4）。
 *
 * <p>{@code params} 是动作的命名参数（如 backend_build 的 profile、frontend_build 的 mode），
 * 取值来自 ci/actions.yml 的声明与默认值；运行 {@code run_action} 时允许覆盖，
 * 覆盖值在注入 steps 前经枚举/正则校验（见 RecipeActionResolver）。
 *
 * @param action   动作
 * @param steps    按顺序执行的命令（每条是 argv 数组）
 * @param produce  产物的 glob（相对工作区），例如 {@code target/*.jar}
 * @param timeout  整个动作的超时
 * @param source   来源（用于证据链）
 * @param params   已合并默认值的参数表（key=参数名，value=实际取值）
 * @param allowed  每个参数的允许取值；空 list 表示该参数接受任意字符串
 */
public record ActionSpec(
        ActionId action,
        List<List<String>> steps,
        List<String> produce,
        Duration timeout,
        ActionSource source,
        Map<String, String> params,
        Map<String, List<String>> allowed) {

    /** 兼容旧调用：无参数。 */
    public ActionSpec(ActionId action, List<List<String>> steps, List<String> produce,
                      Duration timeout, ActionSource source) {
        this(action, steps, produce, timeout, source, Map.of(), Map.of());
    }

    /** 兼容旧调用：有参数无约束。 */
    public ActionSpec(ActionId action, List<List<String>> steps, List<String> produce,
                      Duration timeout, ActionSource source, Map<String, String> params) {
        this(action, steps, produce, timeout, source, params, Map.of());
    }

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
        params = params == null ? Map.of() : Map.copyOf(params);
        allowed = allowed == null ? Map.of() : Map.copyOf(allowed);
    }

    /** 人类可读的一句话，用于确认卡片与日志。 */
    public String describe() {
        String paramPart = params.isEmpty() ? "" : " params=" + params;
        return action.wireName() + " ← " + source.ref() + paramPart + "："
                + steps.stream().map(s -> String.join(" ", s)).reduce((a, b) -> a + " && " + b).orElse("")
                + "（timeout " + timeout.toMinutes() + "m）";
    }
}
