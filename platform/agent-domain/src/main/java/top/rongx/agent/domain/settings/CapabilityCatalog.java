package top.rongx.agent.domain.settings;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 公开能力目录（MCP 服务 / Agent Skill 的内置市场）。
 *
 * <p>只放「元数据 + 安装模板」，不包含任何密钥：MCP 的 stdio 命令与参数、
 * Skill 的 SKILL.md 正文都是公开内容；接入时按模板落库/落盘，用户再到设置页或
 * 对话框右侧工具栏启用即可。
 *
 * <p>目录放在 domain 层：设置页市场（gateway）、安装器（infra）与 Agent 工具
 * （agent-tools）共用同一份定义，避免三处各写一份。
 */
public final class CapabilityCatalog {

    public enum Kind {
        MCP, SKILL;

        public static Optional<Kind> parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return Optional.empty();
            }
            try {
                return Optional.of(valueOf(raw.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                return Optional.empty();
            }
        }
    }

    /** MCP 安装模板（与 mcpServers 约定一致）。 */
    public record McpSpec(String transport, String command, List<String> args, String url) {
    }

    /** Skill 安装模板（content 是 SKILL.md 正文，frontmatter 由 SkillsStore 补齐）。 */
    public record SkillSpec(String description, String content) {
    }

    public record Capability(String id, Kind kind, String name, String title, String description,
                             List<String> tags, String source, McpSpec mcp, SkillSpec skill) {
    }

    private CapabilityCatalog() {
    }

    private static final List<Capability> ALL = List.of(
            // ------------------------------------------------------------ MCP
            new Capability("mcp-playwright", Kind.MCP, "playwright", "Playwright 浏览器自动化",
                    "打开页面、点击、填表、截图，用真实浏览器验证前端表现",
                    List.of("browser", "e2e", "ui", "网页"), "modelcontextprotocol",
                    new McpSpec("stdio", "npx", List.of("-y", "@playwright/mcp@latest"), null), null),
            new Capability("mcp-filesystem", Kind.MCP, "filesystem", "本地文件系统",
                    "在受限目录内读写文件，适合排查日志与配置",
                    List.of("files", "fs", "文件"), "modelcontextprotocol",
                    new McpSpec("stdio", "npx",
                            List.of("-y", "@modelcontextprotocol/server-filesystem", "/tmp"), null), null),
            new Capability("mcp-sequential-thinking", Kind.MCP, "sequential-thinking", "结构化思考",
                    "把复杂任务拆成可回溯的思考步骤，减少跳步与遗漏",
                    List.of("reasoning", "plan", "思考"), "modelcontextprotocol",
                    new McpSpec("stdio", "npx",
                            List.of("-y", "@modelcontextprotocol/server-sequential-thinking"), null), null),
            new Capability("mcp-memory", Kind.MCP, "memory", "长期记忆",
                    "用知识图谱保存跨会话事实，适合记住项目约定",
                    List.of("memory", "knowledge", "记忆"), "modelcontextprotocol",
                    new McpSpec("stdio", "npx",
                            List.of("-y", "@modelcontextprotocol/server-memory"), null), null),
            new Capability("mcp-time", Kind.MCP, "time", "时间与时区",
                    "获取当前时间、时区换算，避免模型编造日期",
                    List.of("time", "date", "时间"), "modelcontextprotocol",
                    new McpSpec("stdio", "npx",
                            List.of("-y", "@modelcontextprotocol/server-time"), null), null),
            new Capability("mcp-context7", Kind.MCP, "context7", "Context7 文档检索",
                    "按库名查找最新官方文档与示例代码",
                    List.of("docs", "library", "文档"), "upstash",
                    new McpSpec("stdio", "npx", List.of("-y", "@upstash/context7-mcp"), null), null),
            new Capability("mcp-deepwiki", Kind.MCP, "deepwiki", "DeepWiki 代码百科",
                    "查询公开仓库的架构说明与代码导览（HTTP 服务，无需本地命令）",
                    List.of("wiki", "repo", "文档"), "deepwiki",
                    new McpSpec("http", null, List.of(), "https://mcp.deepwiki.com/mcp"), null),

            // ------------------------------------------------------------ Skill
            new Capability("skill-code-review", Kind.SKILL, "code-review", "代码审查",
                    "审查变更 diff：正确性、安全、可维护性，输出可执行的修改建议",
                    List.of("review", "diff", "审查"), "内置",
                    null, new SkillSpec("审查代码变更，按严重程度给出结论与修改建议",
                    """
                    当用户要求审查代码、PR 或 diff 时使用本技能。

                    ## 步骤
                    1. 先取变更范围：`git_diff` 或读取用户给出的文件；确认改动意图。
                    2. 按以下顺序检查，只报真实问题，不堆风格偏好：
                       - 正确性：边界条件、空值、并发、事务、幂等；
                       - 安全性：鉴权、越权、注入、密钥硬编码、日志泄露；
                       - 兼容性：接口/DB/配置是否破坏调用方；
                       - 可维护性：重复逻辑、命名误导、缺测试。
                    3. 每条问题给出 `文件:行号`、触发条件、影响与最小修复建议。
                    4. 最后给一句总体结论：可合并 / 修改后合并 / 需重做。

                    ## 输出格式
                    - 结论（1 行）
                    - 问题列表（按严重程度排序，标注 阻断 / 重要 / 建议）
                    - 建议补充的测试
                    """)),
            new Capability("skill-test-generator", Kind.SKILL, "test-generator", "测试生成",
                    "为改动补充最小但有效的单测/接口测试，覆盖边界与失败路径",
                    List.of("test", "unit", "测试"), "内置",
                    null, new SkillSpec("为现有代码补充可运行的测试，优先覆盖边界与失败路径",
                    """
                    当用户要求补测试、提高覆盖率或复现缺陷时使用本技能。

                    ## 步骤
                    1. 读取被测代码与其依赖，确认框架（JUnit / Vitest 等）与已有测试风格。
                    2. 先列测试点：正常路径、边界值、异常分支、并发/幂等（如有）。
                    3. 只写能失败的断言：禁止 `assertNotNull` 式空测试。
                    4. 外部依赖用 fake/mock；不要为了测试改生产逻辑。
                    5. 运行测试，贴出命令与结果；失败时先判断是测试错还是代码错。

                    ## 约束
                    - 新增测试文件放在既有测试目录，遵循现有命名。
                    - 单个测试只验证一个行为，名称描述"给定条件应得到什么"。
                    """)),
            new Capability("skill-deploy-check", Kind.SKILL, "deploy-check", "发布前自检",
                    "部署前检查变量、中间件、端口与健康检查，避免带着配置问题上环境",
                    List.of("deploy", "release", "发布"), "内置",
                    null, new SkillSpec("在部署前做一次可复现的自检并给出放行/阻断结论",
                    """
                    当用户要发布、部署或"帮我看下能不能上线"时使用本技能。

                    ## 检查清单
                    1. `env_summary`：目标环境变量是否齐全、中间件是否健康（只看变量名与来源）。
                    2. `config_resolve`：返回 NEED_USER_INPUT 时必须停下来提问，禁止猜配置。
                    3. 构建与测试：最近一次运行是否成功；失败先定位到具体 stage。
                    4. 端口与访问地址：与目标环境绑定是否一致，是否被占用。
                    5. 健康检查：部署后实际请求一次健康路径，确认 2xx。
                    6. 回滚预案：确认上一版本镜像/提交仍可用。

                    ## 输出
                    - 放行 / 阻断（一句话）
                    - 每项检查的结论与证据（运行 id、日志时间窗、HTTP 码）
                    - 阻断时的最小修复动作
                    """)),
            new Capability("skill-log-analysis", Kind.SKILL, "log-analysis", "日志分析",
                    "从应用/构建日志里定位根因，输出时间线与证据链",
                    List.of("log", "debug", "排障"), "内置",
                    null, new SkillSpec("分析日志定位根因，按时间线给出证据与下一步动作",
                    """
                    当用户贴日志或要求排查线上/构建失败时使用本技能。

                    ## 步骤
                    1. 先确认时间窗、服务名与环境，避免把历史日志当成本次问题。
                    2. 用 `app_logs` / `read_run_log` 取原始日志，必要时按关键字过滤。
                    3. 找第一个异常（First Failure），而不是最后一条报错。
                    4. 构建时间线：请求 → 依赖/DB → 业务异常 → 重试/熔断。
                    5. 给出根因假设与验证方法（要能证伪），区分"已确认"与"待验证"。

                    ## 输出
                    - 根因（一句话）
                    - 证据（日志片段 + 时间）
                    - 下一步：需要改代码 / 改配置 / 扩资源 / 需人工介入
                    """)),
            new Capability("skill-api-design", Kind.SKILL, "api-design", "接口设计",
                    "设计 REST/内部接口：资源建模、错误码、幂等与兼容性",
                    List.of("api", "rest", "设计"), "内置",
                    null, new SkillSpec("设计或评审接口，优先保证兼容、幂等与可观测",
                    """
                    当用户要新增/修改接口时使用本技能。

                    ## 设计要点
                    1. 资源命名用名词复数，动作用 HTTP 方法表达；不要设计 `doSomething` 接口。
                    2. 明确：鉴权方式、分页、排序、过滤、幂等键、限流。
                    3. 错误模型统一：业务码 + 可读 message + traceId，不泄露内部堆栈。
                    4. 兼容性：新增字段可向后兼容；改语义必须版本化或双写过渡。
                    5. 可观测：关键路径打点、慢查询阈值、失败原因分类。

                    ## 输出
                    - 接口表：方法 / 路径 / 入参 / 出参 / 错误码
                    - 幂等与并发说明
                    - 兼容与迁移方案
                    """)),
            new Capability("skill-release-notes", Kind.SKILL, "release-notes", "发布说明",
                    "把提交与变更单整理成面向用户的发布说明",
                    List.of("release", "changelog", "说明"), "内置",
                    null, new SkillSpec("根据提交与变更单生成结构化发布说明",
                    """
                    当用户要写 release notes、周报或上线公告时使用本技能。

                    ## 步骤
                    1. 用 `git_commits` 取本次范围提交；用变更单/运行记录补齐影响面。
                    2. 分类：新功能 / 修复 / 优化 / 破坏性变更 / 已知问题。
                    3. 每条写"用户能感知到什么"，不要直接抄 commit message。
                    4. 破坏性变更必须写清迁移步骤与回滚方式。
                    5. 标注版本号、发布日期与验证环境。

                    ## 输出
                    Markdown 列表，按分类分组，末尾附验证与回滚说明。
                    """))
    );

    public static List<Capability> all() {
        return ALL;
    }

    public static Optional<Capability> find(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return ALL.stream().filter(c -> c.id().equalsIgnoreCase(id.trim())).findFirst();
    }

    /** 按类型与关键词过滤；关键词匹配 id / 名称 / 标题 / 描述 / 标签。 */
    public static List<Capability> search(Kind kind, String keyword) {
        String q = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        return ALL.stream()
                .filter(c -> kind == null || c.kind() == kind)
                .filter(c -> q.isEmpty() || (c.id() + " " + c.name() + " " + c.title() + " "
                        + c.description() + " " + String.join(" ", c.tags()))
                        .toLowerCase(Locale.ROOT).contains(q))
                .toList();
    }
}
