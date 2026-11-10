package top.rongx.agent.domain.env;

/**
 * 本地服务端口租约（dev 环境前后端由平台托管在宿主机进程里）。
 *
 * @param projectId 项目
 * @param env       环境（目前只有 dev 使用）
 * @param service   backend / frontend
 * @param hostPort  宿主机端口
 * @param status    RUNNING / STOPPED / UNKNOWN
 * @param pid       平台拉起的进程 pid（0 = 未知）
 * @param updatedAt 更新时间
 */
public record ServiceLease(
        String projectId,
        String env,
        String service,
        int hostPort,
        String status,
        long pid,
        long updatedAt) {

    public static final String BACKEND = "backend";
    public static final String FRONTEND = "frontend";

    public boolean running() {
        return "RUNNING".equals(status);
    }
}
