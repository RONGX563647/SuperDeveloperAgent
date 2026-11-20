---
type: code-wiki-index
summary: 研发运维智能体平台 Code Wiki 总目录：定位、模块地图、阅读路径与快速事实
---

# 研发运维智能体平台 · Code Wiki

本 Wiki 面向"要理解或修改这个仓库"的工程师，按"先全局、再模块、后实现"的顺序组织。

## 这是什么项目

一个内网研发运维智能体平台：把「接入项目 → 对话/规划 → 受控执行 → 流水线 → 部署」收敛到同一个工作区。

| 维度 | 事实 |
|---|---|
| 后端 | Java 21、Spring Boot 4.0.4、AgentScope Java 2.0.0、Maven 多模块 |
| 前端 | Vue 3.5 + Vite 7 + Element Plus + vue-router，无 Pinia（轻量响应式 store） |
| 持久化 | 默认单文件 SQLite，可切换 PostgreSQL；Flyway 管理 schema，JPA 访问 |
| 认证 | Sa-Token（Bearer Token，30 天），角色 ADMIN / DEVOPS / VIEWER |
| 模型 | OpenAI 兼容端点，默认阿里云 DashScope（qwen-plus），设置页可热切换 |
| 执行 | 仓库配方 `ci/actions.yml → Taskfile.yml → Makefile → 内置默认`，Docker 沙箱可选 |
| 质量门禁 | ArchUnit 架构规则、禁令扫描、Flyway 迁移锁、Handbook 漂移、会话归档哈希链 + views 一致性、提交编码门禁、token 量化基线 |

当前基线：`0.2.0-snapshot`（2026-11-20，远端 tip `f584dd77`）。

## 文档目录

| # | 文档 | 内容 |
|---|---|---|
| 1 | [总体架构](01-architecture.md) | 分层、依赖方向、模块地图、三条主干链路 |
| 2 | [模块详解](02-modules.md) | 9 个模块的职责、关键类、对外接口 |
| 3 | [核心流程](03-core-flows.md) | 对话 SSE、流水线、治理确认、项目接入、BugFix、工作区调度 |
| 4 | [领域模型](04-domain-model.md) | 实体/枚举/端口/错误码字典 |
| 5 | [工具与治理](05-tools-and-governance.md) | 工具面、插件渐进披露、权限矩阵、确认令牌 |
| 6 | [执行与部署](06-execution-deploy.md) | 动作执行器、沙箱、镜像就绪自动收敛、流水线引擎、部署器、环境供给、自动修复 |
| 7 | [持久化与数据布局](07-persistence.md) | 双数据库、Flyway、实体清单、工作区目录、加密状态 |
| 8 | [HTTP API](08-http-api.md) | 接口分组清单、错误契约、SSE 协议 |
| 9 | [前端架构](09-frontend.md) | 路由、视图、API 层、状态归约、组件 |
| 10 | [构建与运行](10-build-and-run.md) | 环境要求、构建、启动、配置项、故障排查 |
| 11 | [质量门禁与工程工具](11-quality-and-tooling.md) | spec-checks、Handbook、会话归档、CI、`tools/` |

## 阅读路径

- **刚接手**：本页 → [总体架构](01-architecture.md) → [核心流程](03-core-flows.md) → [模块详解](02-modules.md)。
- **改 Agent 行为**：`platform/agent-runtime` → [核心流程 §1](03-core-flows.md) → [工具与治理](05-tools-and-governance.md)。
- **加/改工具**：`platform/agent-tools` + `platform/agent-domain` → [工具与治理](05-tools-and-governance.md)。
- **改流水线/部署**：`platform/agent-executor` → [执行与部署](06-execution-deploy.md)。
- **改数据库/存储**：`platform/agent-infra/persistence` → [持久化](07-persistence.md)。
- **改页面**：`platform/web` → [前端架构](09-frontend.md)。
- **跑不起来**：直接看 [构建与运行](10-build-and-run.md)。

## 目录速览

```text
/workspace
├── platform/                 # 主工程（Maven 多模块 + Vue 前端）
│   ├── agent-domain/         # 领域模型与端口（零框架依赖）
│   ├── agent-infra/          # Git/worktree、SQLite/JPA、加密、配方、项目接入
│   ├── agent-governance/     # 环境策略、变更单、确认令牌、补丁登记
│   ├── agent-executor/       # 流水线引擎、动作执行器、沙箱、部署器、环境供给
│   ├── agent-tools/          # @Tool 工具集合与四套 Toolkit 装配
│   ├── agent-runtime/        # Agent 工厂、对话服务、模型解析、工作区调度
│   ├── agent-gateway/        # Spring Boot 入口、HTTP/SSE、Sa-Token、BugFix
│   ├── spec-checks/          # 构建期规约检查（仅测试代码）
│   └── web/                  # Vue3 前端
├── docs/                     # skills 路由卡、ledger 会话留痕、views 生成视图、Handbook 锚点、本 Wiki
├── tools/                    # docs 视图生成/检索、session-archive 归档门禁、handbook 锚点、ci 编码门禁、api-docs-mcp、task-board
├── specs/                    # 功能规格（editor tools、token budget）
├── .workflow/build.yml       # Gitee Go CI
├── Dockerfile                # 平台自部署镜像
└── agent.md                  # 工程约束（Ponytail 最小改动、版本纪律）
```

## 三条最重要的不变量

1. **上下文由服务端绑定**：`ProjectContext` 在网关解析后经 `RuntimeContext` 显式传给工具，模型无法伪造（[ProjectContextResolver](file:///workspace/platform/agent-gateway/src/main/java/top/rongx/agent/gateway/web/ProjectContextResolver.java)）。
2. **写操作不靠提示词约束**：生产环境装配只读 Toolkit（写工具根本不在 schema 里），真正门禁在工具/治理层。
3. **确认令牌只能由人签发**：`ChangeController` 是唯一签发入口，Agent 无对应工具；令牌绑定 `planHash`，一次性、5 分钟（[ConfirmTokenService](file:///workspace/platform/agent-governance/src/main/java/top/rongx/agent/governance/ConfirmTokenService.java)）。
