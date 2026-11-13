package top.rongx.agent.domain.deploy;

/**
 * 部署失败的机器可读原因码（Agent 与前端都按它选择下一步，而不是解析自然语言）。
 */
public enum ReasonCode {

    OK("成功", "无需处理"),
    IMAGE_MISSING("缺少镜像", "先执行 image_package，或检查 Dockerfile / 镜像名"),
    IMAGE_PULL("镜像拉取失败", "检查镜像名与仓库权限；本地构建的镜像确认未被清理"),
    PORT_IN_USE("端口被占用", "用 port_check 找到占用者；停掉旧容器或换端口"),
    HEALTH_TIMEOUT("健康检查超时", "用 app_logs 看启动日志；确认端口/路径与启动耗时"),
    CONTAINER_EXIT("容器启动后退出", "用 docker_logs / app_logs 看退出原因；常见为配置缺失"),
    OOM("内存不足", "调大容器内存或减少并发；检查 JVM -Xmx"),
    CONFIG_MISSING("配置缺失", "到「设置 → 环境模板」补齐变量；config_catalog 可查键名"),
    DEPENDENCY("依赖不可用", "检查中间件是否运行、网络是否互通（env_summary / port_check）"),
    SERVER_UNREACHABLE("目标服务器不可达", "检查服务器地址、SSH 密钥与网络"),
    ROLLBACK_UNAVAILABLE("没有可回滚版本", "首次发布失败属正常；先修复当前版本"),
    ROLLED_BACK("部署失败，已自动回滚", "修复根因后重新发布；当前运行的是上一版"),
    UNKNOWN("未知失败", "用 read_run_log / app_logs 取证后重试");

    private final String label;
    private final String suggestion;

    ReasonCode(String label, String suggestion) {
        this.label = label;
        this.suggestion = suggestion;
    }

    public String label() {
        return label;
    }

    public String suggestion() {
        return suggestion;
    }

    public String wireName() {
        return name();
    }
}
