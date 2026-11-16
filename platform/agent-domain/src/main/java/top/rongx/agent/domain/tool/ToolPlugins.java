package top.rongx.agent.domain.tool;

import java.util.List;
import java.util.Set;

/**
 * 平台工具插件目录（纯静态定义）。
 *
 * <p>把 70+ 个工具按「能力」切成插件：默认只暴露 {@link #CORE} 里的核心工具，
 * 其余插件由模型按需 {@code tool_load} 激活，避免每轮对话都重复传输全部工具 schema。
 * 这里只是目录，真实工具面以装配出来的 Toolkit 为准（{@link ToolPluginIndex#of} 会过滤）。
 */
public final class ToolPlugins {

    private ToolPlugins() {
    }

    /**
     * 始终可见的核心工具：
     * <ul>
     *   <li>基础定位（project_info / list_envs）与工具发现（tool_search / tool_get / tool_load / tool_plugins）；</li>
     *   <li>接口规范（api_*），模型常用来确认"平台接口怎么调"；</li>
     *   <li>工作区调度与任务板（调度 Agent 的全部管理能力，工具面小、必须常驻）。</li>
     * </ul>
     */
    public static final Set<String> CORE = Set.of(
            "project_info", "list_envs",
            "tool_search", "tool_get", "tool_load", "tool_plugins", "skill_load",
            "api_search", "api_get", "api_schema",
            "task_list", "task_create", "task_update", "task_complete",
            "workspace_snapshot", "workspace_plan", "workspace_recall", "workspace_dispatch",
            "conversation_list", "conversation_create", "conversation_send",
            "conversation_wait", "conversation_status", "conversation_cancel",
            "conversation_rename", "conversation_pin", "conversation_archive",
            "conversation_search", "conversation_summarize", "dispatch_issue");

    /** 标准插件：名称稳定、顺序固定（索引文案与缓存前缀都依赖它）。 */
    public static List<ToolPluginIndex.Plugin> standard() {
        return List.of(
                plugin("repo", "代码与提交",
                        "仓库检索与改动查看：git_status/commits/diff、read_file、list_files、grep",
                        Set.of("代码", "仓库", "分支", "提交", "commit", "diff", "文件", "源码", "git"),
                        Set.of("git_status", "git_commits", "git_diff", "list_files", "read_file",
                                "grep")),
                plugin("ci", "构建流水线",
                        "查看与触发构建：流水线、定义、预检、状态、日志、触发/取消",
                        Set.of("构建", "流水线", "编译", "ci", "pipeline", "构建失败", "测试失败",
                                "打包失败", "日志"),
                        Set.of("ci_list_pipelines", "ci_definition", "ci_dry_run", "ci_history",
                                "ci_status", "read_run_log", "wait_run", "ci_run", "ci_cancel",
                                "ci_configure_deploy")),
                plugin("deploy", "部署发布",
                        "环境部署与固化：部署状态、触发、固化脚本、Dockerfile 自愈、发布链路",
                        Set.of("部署", "发布", "上线", "deploy", "release", "回滚", "跑通",
                                "dockerfile", "docker", "镜像"),
                        Set.of("deploy_env_status", "deploy_run", "deploy_save_recipe",
                                "deploy_host_stop", "ensure_dockerfile", "release_trace",
                                "run_ops_agent")),
                plugin("env", "环境与配置",
                        "环境变量、端口、中间件与应用运行态：摘要、映射、覆盖、健康检查、日志",
                        Set.of("环境变量", "配置", "端口", "中间件", "数据库", "redis", "mysql",
                                "健康检查", "启动失败", "日志", "env"),
                        Set.of("env_summary", "env_vars", "env_mapping_draft", "env_var_set",
                                "env_var_unset", "env_var_probe", "env_ensure",
                                "env_runtime_status", "app_logs", "port_check",
                                "config_resolve")),
                plugin("issue", "工单任务",
                        "任务页的 Git 工单：列表、详情、新建、评论、关闭重开与同步",
                        Set.of("工单", "issue", "任务", "bug", "缺陷", "需求", "同步"),
                        Set.of("list_git_issues", "get_git_issue", "create_git_issue",
                                "update_git_issue", "comment_git_issue", "close_git_issue",
                                "reopen_git_issue", "sync_git_issue")),
                plugin("action", "动作执行",
                        "预定义动作（构建/打包/发布/回滚）的查看与执行",
                        Set.of("动作", "执行命令", "构建动作", "回滚", "action"),
                        Set.of("list_actions", "plan_action", "run_action")),
                plugin("change", "变更与补丁",
                        "人工确认的变更单与受限代码补丁（propose_patch / request_confirmation）",
                        Set.of("补丁", "修改代码", "变更", "确认", "修复", "patch", "propose"),
                        Set.of("request_confirmation", "confirm", "propose_patch")),
                plugin("capability", "能力市场",
                        "查找并接入公开的 MCP 服务与 Skill",
                        Set.of("mcp", "skill", "技能", "能力", "插件", "安装"),
                        Set.of("search_capabilities", "install_capability")));
    }

    private static ToolPluginIndex.Plugin plugin(String name, String title, String description,
                                                 Set<String> keywords, Set<String> tools) {
        return new ToolPluginIndex.Plugin(name, title, description, keywords, tools);
    }
}
