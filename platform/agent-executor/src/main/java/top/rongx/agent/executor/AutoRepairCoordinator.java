package top.rongx.agent.executor;

import java.util.List;

/**
 * 自动修复协调器（纯逻辑，不依赖 Spring / Docker / 引擎状态）：
 * {@code 诊断 → 修复 → 平台复核 → 自动从失败阶段续跑}，每轮都有次数上限与取消检查。
 *
 * <p>之所以独立成类：引擎只负责接线与状态机，这段循环可以脱离真实流水线单测，
 * 也便于将来追加新的 {@link FailureRepair}（依赖缺失、端口占用、配置写错…）。
 */
final class AutoRepairCoordinator {

    /** 引擎提供的能力；全部是回调，协调器不持有运行对象。 */
    interface Host {
        /** 按当前状态重新诊断（自动续跑后失败原因可能变化）。 */
        FailureRepair.Diagnosis diagnosis(int attempt);

        /** 本轮修复上下文。 */
        FailureRepair.Context context(int attempt);

        /** 写一行运行日志。 */
        void log(String line);

        boolean cancelled();

        /** 从失败阶段同步续跑；返回 true 表示运行已成功结束。 */
        boolean resume(String stageId);
    }

    record Result(int attempts, boolean resumed, boolean succeeded,
                  String lastAction, String lastResult) {
        static Result skipped(String reason) {
            return new Result(0, false, false, "", reason);
        }
    }

    private final AutoRepairPolicy policy;
    private final List<FailureRepair> repairs;

    AutoRepairCoordinator(AutoRepairPolicy policy, List<FailureRepair> repairs) {
        this.policy = policy == null ? AutoRepairPolicy.disabled() : policy;
        this.repairs = repairs == null ? List.of() : List.copyOf(repairs);
    }

    Result run(Host host) {
        if (!policy.enabled() || policy.maxAttempts() == 0 || repairs.isEmpty()) {
            return Result.skipped("自动修复未启用");
        }
        int attempts = 0;
        boolean resumed = false;
        String action = "";
        String result = "未执行修复";
        for (int n = 1; n <= policy.maxAttempts(); n++) {
            if (host.cancelled()) {
                result = "运行已取消，停止自动修复";
                break;
            }
            FailureRepair.Diagnosis d = host.diagnosis(n);
            if (d == null || d.stageId() == null || d.stageId().isBlank()) {
                result = "无法定位失败阶段，跳过自动续跑";
                break;
            }
            attempts = n;
            boolean verified = false;
            for (FailureRepair repair : repairs) {
                if (!repair.matches(d)) {
                    continue;
                }
                action = repair.getClass().getSimpleName();
                FailureRepair.Outcome outcome;
                try {
                    outcome = repair.repair(d, host.context(n));
                } catch (Throwable e) {
                    outcome = FailureRepair.Outcome.notFixed(
                            "修复动作异常：" + brief(e), List.of());
                    host.log("[autofix] ⚠️ " + outcome.summary() + "\n");
                }
                for (String line : outcome.lines()) {
                    host.log(line.endsWith("\n") ? line : line + "\n");
                }
                result = outcome.summary();
                if (outcome.verified()) {
                    verified = true;
                    break;
                }
            }
            if (!verified || !policy.autoResume()) {
                break;
            }
            host.log("[autofix] 第 " + n + " 次修复已验证 · 自动从阶段 " + d.stageId()
                    + " 继续（无需人工点击重跑）\n");
            resumed = true;
            if (host.resume(d.stageId())) {
                return new Result(attempts, true, true, action, "自动修复后续跑成功");
            }
            if (host.cancelled()) {
                result = "运行已取消";
                break;
            }
            result = "自动续跑后仍失败";
        }
        return new Result(attempts, resumed, false, action, result);
    }

    private static String brief(Throwable e) {
        String msg = e.getMessage() == null ? e.toString() : e.getMessage();
        String one = msg.replaceAll("\\s+", " ").trim();
        return one.length() > 200 ? one.substring(0, 200) + "…" : one;
    }
}
