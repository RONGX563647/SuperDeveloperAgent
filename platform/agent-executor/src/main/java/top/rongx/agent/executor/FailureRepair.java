package top.rongx.agent.executor;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import top.rongx.agent.domain.run.RunRecoveryAdvisor;

/**
 * 失败修复器：把「错误 → 修复动作 → 平台复核」做成可插拔的小单元。
 *
 * <p>关键约束：{@link Outcome#verified()} 只能由**平台自己执行的验证命令**置为 true，
 * 绝不采信模型或构建日志里的「已修复」自述。只有 verified 才会触发自动续跑。
 */
interface FailureRepair {

    /** 该修复器是否适用于这次失败。 */
    boolean matches(Diagnosis diagnosis);

    /** 执行修复；异常由协调器兜底，实现里不必吞异常。 */
    Outcome repair(Diagnosis diagnosis, Context context);

    /** 一次失败的诊断快照（日志尾部已截断，供模型阅读）。 */
    record Diagnosis(String runId, String stageId, String errorCode, String message,
                     String logTail, String image, int attempt, boolean agentEnabled,
                     boolean precheckTried) {

        /** 兼容构造：默认「预检没有尝试过自动拉取」。 */
        Diagnosis(String runId, String stageId, String errorCode, String message,
                  String logTail, String image, int attempt, boolean agentEnabled) {
            this(runId, stageId, errorCode, message, logTail, image, attempt,
                    agentEnabled, false);
        }

        private static final Pattern IMAGE_ZH =
                Pattern.compile("构建镜像未就绪[（(]([^）)]+)[）)]");
        private static final Pattern IMAGE_EN =
                Pattern.compile("No such image:\\s*([^\\s\\r\\n]+)");

        /** 从错误信息里解析缺失镜像名；解析不到返回 null（日志内容只用于匹配，不作为命令参数）。 */
        static String missingImage(String message, String logTail) {
            Matcher zh = IMAGE_ZH.matcher(message == null ? "" : message);
            if (zh.find()) {
                return zh.group(1).trim();
            }
            Matcher en = IMAGE_EN.matcher(message == null ? "" : message);
            if (en.find()) {
                return en.group(1).trim();
            }
            Matcher inLog = IMAGE_ZH.matcher(logTail == null ? "" : logTail);
            if (inLog.find()) {
                return inLog.group(1).trim();
            }
            inLog = IMAGE_EN.matcher(logTail == null ? "" : logTail);
            return inLog.find() ? inLog.group(1).trim() : null;
        }

        /** 单行摘要，避免把整段错误塞进日志。 */
        String brief() {
            String one = (message == null ? "" : message).replaceAll("\\s+", " ").trim();
            if (one.length() > 240) {
                one = one.substring(0, 240) + "…";
            }
            return (errorCode == null ? "" : errorCode + " ") + one;
        }
    }

    /** 修复结果；lines 是完整日志行（可自带 [autofix]/[agent] 前缀）。 */
    record Outcome(boolean verified, String summary, List<String> lines) {
        static Outcome fixed(String summary, List<String> lines) {
            return new Outcome(true, summary, lines);
        }

        static Outcome notFixed(String summary, List<String> lines) {
            return new Outcome(false, summary, lines);
        }
    }

    /** 有界外部命令（由引擎注入，测试可替换为假执行器）。 */
    @FunctionalInterface
    interface CommandRunner {
        ProcessExecutor.StepOutcome run(List<String> argv, long timeoutMs, Consumer<String> live);
    }

    /**
     * 修复执行上下文。
     *
     * <p>pullCommand / inspectCommand 由沙箱按**管理员配置**生成，修复器不拼 docker 参数，
     * 也不会把日志里出现的镜像名当作命令参数（防提示注入）。
     *
     * <p>{@code provisioner} 与动作前预检共用同一套就绪策略（超时判据、重试、镜像源、
     * 退避状态）——修复路径不再自己定一套超时数字，否则预检与修复会各说各话。
     */
    record Context(BooleanSupplier cancelled, Consumer<String> log, CommandRunner command,
                   long pullTimeoutMs, List<String> pullCommand, List<String> inspectCommand,
                   String configuredImage, RunRecoveryAdvisor advisor,
                   String projectId, String env, String pipelineId, String gitSha,
                   String actor, String logTail, BooleanSupplier preconditionsOk,
                   ImageProvisioner provisioner) {

        /** 兼容构造：没有就绪能力（非容器沙箱 / 老测试）时 provisioner 为空。 */
        Context(BooleanSupplier cancelled, Consumer<String> log, CommandRunner command,
                long pullTimeoutMs, List<String> pullCommand, List<String> inspectCommand,
                String configuredImage, RunRecoveryAdvisor advisor,
                String projectId, String env, String pipelineId, String gitSha,
                String actor, String logTail, BooleanSupplier preconditionsOk) {
            this(cancelled, log, command, pullTimeoutMs, pullCommand, inspectCommand,
                    configuredImage, advisor, projectId, env, pipelineId, gitSha, actor, logTail,
                    preconditionsOk, null);
        }
    }
}
