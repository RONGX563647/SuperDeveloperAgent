package top.rongx.agent.domain.deploy;

import java.util.Locale;

/**
 * 部署失败分类器：只看错误码与日志尾部，给出稳定、可测试的 {@link ReasonCode}。
 *
 * <p>规则刻意保守：拿不准时返回 {@link ReasonCode#UNKNOWN}，让 Agent 继续取证，
 * 而不是猜一个看似合理的结论。
 */
public final class DeployDiagnosis {

    private DeployDiagnosis() {
    }

    public static ReasonCode classify(boolean success, String errorCode, String summary, String log) {
        if (success) {
            return ReasonCode.OK;
        }
        String text = ((summary == null ? "" : summary) + "\n"
                + (log == null ? "" : log)).toLowerCase(Locale.ROOT);
        String code = errorCode == null ? "" : errorCode.trim().toUpperCase(Locale.ROOT);

        if (code.equals("E-509") || contains(text, "缺少必填环境变量", "missing required env",
                "config missing", "environment variable")) {
            return ReasonCode.CONFIG_MISSING;
        }
        if (code.equals("E-507") || contains(text, "回滚镜像", "rollback image")) {
            return ReasonCode.ROLLBACK_UNAVAILABLE;
        }
        if (code.equals("E-505") || contains(text, "ssh", "服务器不可达", "connection refused",
                "no route to host")) {
            return ReasonCode.SERVER_UNREACHABLE;
        }
        if (contains(text, "pull access denied", "manifest unknown", "failed to pull",
                "no such image", "image not found")) {
            return ReasonCode.IMAGE_PULL;
        }
        if (contains(text, "port is already allocated", "address already in use",
                "bind for 0.0.0.0", "端口被占用")) {
            return ReasonCode.PORT_IN_USE;
        }
        if (contains(text, "outofmemory", "oom-kill", "out of memory", "java heap space")) {
            return ReasonCode.OOM;
        }
        if (contains(text, "health", "健康检查", "healthcheck") && contains(text, "timeout", "超时",
                "timed out")) {
            return ReasonCode.HEALTH_TIMEOUT;
        }
        if (contains(text, "connection refused", "unknown host", "no such host", "dial tcp",
                "could not connect", "database", "redis", "依赖")) {
            return ReasonCode.DEPENDENCY;
        }
        if (contains(text, "exited with code", "container exited", "退出")) {
            return ReasonCode.CONTAINER_EXIT;
        }
        if (code.equals("E-501")) {
            return ReasonCode.IMAGE_MISSING;
        }
        return ReasonCode.UNKNOWN;
    }

    private static boolean contains(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
