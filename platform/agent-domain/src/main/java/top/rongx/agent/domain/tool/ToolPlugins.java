package top.rongx.agent.domain.tool;

import java.util.List;
import java.util.Set;

/**
 * 平台工具插件目录（纯静态定义）。
 *
 * <p>把 70+ 个工具按「能力」切成插件：默认只暴露 {@link #CORE} 里的核心工具，
 * 其余插件由模型按需 {@code tool_load} 激活，避免每轮对话都重复传输全部工具 schema。
 * 这里只是目录，真实工具面以装配出来的 Toolkit 为准（{@link ToolPluginIndex#of} 会过滤）。
 *
 * <p><b>描述文案保持英文且简短</b>：这段文案会进系统提示的插件索引（每轮常驻），
 * 中文描述虽可读，但 token 成本更高；行为约束放在系统提示里，不放在这里。
 */
public final class ToolPlugins {

    private ToolPlugins() {
    }

    /**
     * 始终可见的核心工具（只放「发现类」能力，共 8 个）：
     * <ul>
     *   <li>定位与取证：project_info / list_envs；</li>
     *   <li>工具发现：tool_search / tool_get / tool_load / tool_plugins；</li>
     *   <li>技能与接口规范：skill_load / api_search。</li>
     * </ul>
     *
     * <p>会话/任务/工作区调度（conversation_* / task_* / workspace_*）不再常驻，
     * 归入 {@code dispatch} 插件；接口详情（api_get / api_schema）归入 {@code api} 插件。
     */
    public static final Set<String> CORE = Set.of(
            "project_info", "list_envs",
            "tool_search", "tool_get", "tool_load", "tool_plugins",
            "skill_load", "api_search");

    /** 标准插件：名称稳定、顺序固定（索引文案与缓存前缀都依赖它）。 */
    public static List<ToolPluginIndex.Plugin> standard() {
        return List.of(
                plugin("repo", "Code & commits",
                        "Repo search and changes: git status/diff, read_file, list_files, grep",
                        Set.of("代码", "仓库", "分支", "提交", "commit", "diff", "文件", "源码", "git",
                                "code", "repo", "branch", "file", "source"),
                        Set.of("git_status", "git_commits", "git_diff", "list_files", "read_file",
                                "grep")),
                plugin("ci", "Build pipeline",
                        "Pipelines and builds: definition, dry run, status, logs, trigger, cancel",
                        Set.of("构建", "流水线", "编译", "ci", "pipeline", "构建失败", "测试失败",
                                "打包失败", "日志", "build", "log"),
                        Set.of("ci_list_pipelines", "ci_definition", "ci_dry_run", "ci_history",
                                "ci_status", "read_run_log", "wait_run", "ci_run", "ci_cancel",
                                "ci_configure_deploy")),
                plugin("deploy", "Deploy & release",
                        "Env deploy and recipes: status, run, save recipe, Dockerfile, release trace",
                        Set.of("部署", "发布", "上线", "deploy", "release", "回滚", "跑通",
                                "dockerfile", "docker", "镜像", "image", "rollback"),
                        Set.of("deploy_env_status", "deploy_run", "deploy_save_recipe",
                                "deploy_host_stop", "ensure_dockerfile", "release_trace",
                                "run_ops_agent")),
                plugin("env", "Env & config",
                        "Env vars, ports, middleware and app runtime: summary, override, probe, logs",
                        Set.of("环境变量", "配置", "端口", "中间件", "数据库", "redis", "mysql",
                                "健康检查", "启动失败", "日志", "env", "config", "port", "health"),
                        Set.of("env_summary", "env_vars", "env_mapping_draft", "env_var_set",
                                "env_var_unset", "env_var_probe", "env_ensure",
                                "env_runtime_status", "app_logs", "port_check",
                                "config_resolve")),
                plugin("issue", "Issues & tasks",
                        "Task-page Git issues: list, detail, create, comment, close/reopen, sync",
                        Set.of("工单", "issue", "任务", "bug", "缺陷", "需求", "同步", "task"),
                        Set.of("list_git_issues", "get_git_issue", "create_git_issue",
                                "update_git_issue", "comment_git_issue", "close_git_issue",
                                "reopen_git_issue", "sync_git_issue")),
                plugin("action", "Actions",
                        "Predefined actions (build/package/release/rollback): list, plan, run",
                        Set.of("动作", "执行命令", "构建动作", "回滚", "action", "command"),
                        Set.of("list_actions", "plan_action", "run_action")),
                plugin("change", "Changes & patches",
                        "Human-confirmed changes and restricted patches",
                        Set.of("补丁", "修改代码", "变更", "确认", "修复", "patch", "propose",
                                "change", "confirm"),
                        Set.of("request_confirmation", "confirm", "propose_patch")),
                plugin("capability", "Capability market",
                        "Find and install public MCP servers and skills",
                        Set.of("mcp", "skill", "技能", "能力", "插件", "安装", "install"),
                        Set.of("search_capabilities", "install_capability")),
                plugin("api", "API docs",
                        "Platform HTTP API: search, endpoint signature, model schema",
                        Set.of("api", "接口", "文档", "endpoint", "openapi"),
                        Set.of("api_search", "api_get", "api_schema")),
                plugin("dispatch", "Workspace dispatch",
                        "Conversations, runs, task board and DAG dispatch",
                        Set.of("会话", "派发", "调度", "任务板", "conversation", "dispatch",
                                "workspace", "task"),
                        Set.of("workspace_snapshot", "workspace_plan", "workspace_recall",
                                "workspace_dispatch", "conversation_list", "conversation_search",
                                "conversation_create", "conversation_rename",
                                "conversation_archive", "conversation_pin", "conversation_send",
                                "conversation_status", "conversation_wait", "conversation_cancel",
                                "conversation_summarize", "dispatch_issue", "task_list",
                                "task_create", "task_update", "task_complete")));
    }

    private static ToolPluginIndex.Plugin plugin(String name, String title, String description,
                                                 Set<String> keywords, Set<String> tools) {
        return new ToolPluginIndex.Plugin(name, title, description, keywords, tools);
    }
}
