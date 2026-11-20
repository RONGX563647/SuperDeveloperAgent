---
type: code-wiki-architecture
summary: 总体架构：分层、依赖方向、模块地图、4 条主干链路与架构约束
---

# 总体架构

## 1. 设计目标

平台把一次研发运维任务拆成四段，每段都有明确的信任边界：

```text
用户对话（自然语言）
   ↓  SSE
Agent（ReAct，按环境装配工具面）
   ↓  工具调用
平台治理（环境策略 / 变更单 / 确认令牌）
   ↓  受控执行
执行面（动作执行器 / 沙箱 / 流水线 / 部署器）
   ↓
证据（运行记录 / 日志 / 产物 / 变更单）
```

核心取舍：**模型负责决策，平台负责执行与门禁**。模型不能拼接 shell，不能自行确认变更，不能改生产。

## 2. 模块地图与依赖方向

```text
                 ┌──────────────────────────────┐
                 │        agent-gateway         │  Spring Boot 入口、HTTP/SSE、Sa-Token、BugFix 编排
                 └──────────────┬───────────────┘
                                │
                 ┌──────────────▼───────────────┐
                 │        agent-runtime         │  Agent 工厂、对话服务、模型解析、工作区调度
                 └───────┬──────────────┬───────┘
                         │              │
        ┌────────────────▼───┐   ┌──────▼────────────────┐
        │    agent-tools     │   │   agent-governance    │ 工具 / 变更单 / 令牌 / 环境策略
        └────────┬───────────┘   └──────────┬────────────┘
                 │                          │
        ┌────────▼──────────────────────────▼────────────┐
        │                  agent-domain                  │ 纯领域：实体、枚举、端口、错误码
        └────────▲──────────────────────────▲────────────┘
                 │                          │
        ┌────────┴───────────┐   ┌──────────┴─────────────┐
        │    agent-infra     │   │    agent-executor      │ 存储/Git/加密  执行面/沙箱/部署
        └────────────────────┘   └────────────────────────┘

        spec-checks（测试期）校验上述规则；web（HTTP）独立部署
```

> 为装配 HTTP 入口与部署能力，`agent-gateway` 还直接依赖 `agent-executor`、`agent-infra`；依赖方向仍保持自上而下，不出现反向引用。

依赖规则（由 `spec-checks` 的 ArchUnit 在 `mvn verify` 强制）：

| 规则 | 内容 |
|---|---|
| A1 | 分层依赖单向，禁止反向依赖 |
| A2 | `agent-domain` 零框架依赖（无 Spring / MyBatis / AgentScope） |
| A3 | `agent-executor` 不得依赖任何模型 SDK |
| A4 | 决策面不得依赖容器客户端 |
| A6 | 禁止 `util` 包 |
| A7 | `agent-tools` 禁止 `ProcessBuilder` |
| A9 | 禁止字段注入（统一构造器注入） |

> 规范来源：[spec-checks 测试类](file:///workspace/platform/spec-checks/src/test/java/top/rongx/agent/arch/ArchitectureRulesTest.java)；规则说明见 [质量门禁](11-quality-and-tooling.md)。

## 3. 模块职责速查

| 模块 | 职责 | 关键入口 |
|---|---|---|
| `agent-domain` | 动作、变更、环境、运行、会话、设置等领域模型；所有跨层端口 | `ActionId`、`ToolLevel`、`ProjectContext`、`ErrorCode` |
| `agent-infra` | Git/worktree、项目接入、JPA 持久化、Flyway、AES-GCM 加解密、会话状态、配方存储 | `ProjectIntakeService`、`GitWorktreeManager`、`SecretBox`、`EncryptedAgentStateStore` |
| `agent-governance` | 环境策略矩阵、变更单登记、确认令牌签发/消费、补丁登记 | `EnvPolicyService`、`ChangeRegistry`、`ConfirmTokenService` |
| `agent-executor` | 流水线引擎、动作执行器、Docker 沙箱、部署器、环境供给、自动修复 | `BuiltInPipelineEngine`、`DockerSandbox`、`Deployers` |
| `agent-tools` | 90 个 `@Tool` 方法、4 套 Toolkit、工具目录导出 | `ToolCatalog`、`ActionService` |
| `agent-runtime` | Agent 构造、工具面裁剪、模型热切换、SSE 事件源、工作区 DAG | `AgentFactory`、`ChatService`、`WorkspaceDispatchScheduler` |
| `agent-gateway` | Boot 入口、41 个 Controller、认证鉴权、项目上下文、BugFix 编排 | `AgentApplication`、`ChatController`、`BugFixOrchestrator` |
| `spec-checks` | 构建期规约检查 | `ArchitectureRulesTest`、`ForbiddenPatternScanTest` |
| `web` | Vue3 单页应用 | `router.ts`、`views/ChatView.vue`、`api/http.ts` |

详细说明见 [模块详解](02-modules.md)。

## 4. 四条主干链路

### 4.1 对话链路（SSE）

```text
POST /api/v1/chat
  → ChatController#chat           认证 + 解析 ProjectContext + 并发闸门
  → ChatService#stream            校验输入、绑定 sessionId、解析模型
  → AgentFactory#createChat       按环境/角色选 Toolkit、挂 MCP/Skill、建 HarnessAgent
  → HarnessAgent.streamEvents       模型 ↔ 工具循环（AgentScope）
  → ChatController#onEvent        事件映射为 SSE（text.delta / tool.* / usage / change.created）
```

实现：[ChatController](file:///workspace/platform/agent-gateway/src/main/java/top/rongx/agent/gateway/web/ChatController.java)、[ChatService](file:///workspace/platform/agent-runtime/src/main/java/top/rongx/agent/runtime/ChatService.java)、[AgentFactory](file:///workspace/platform/agent-runtime/src/main/java/top/rongx/agent/runtime/AgentFactory.java)。

### 4.2 工具与治理链路

```text
模型 tool_call
  → Toolkit 中的 @Tool 方法（ProjectContext 由 RuntimeContext 注入）
  → 只读：直接执行
  → 写操作：ActionService / ChangeTools / PatchTools / DeployTools
       ├─ 环境策略 DENY → PolicyDeniedException（E-7xx）
       ├─ 需要确认 → 登记 ChangePlan，返回 changeId（卡片）
       └─ L3 → 人经 POST /changes/{id}/confirm 拿令牌 → POST /changes/{id}/apply
```

### 4.3 流水线执行链路

```text
ci_run / POST /api/v1/ci/pipelines/{id}/run
  → BuiltInPipelineEngine#trigger   checkout sha → 解析 pipeline.yml
  → 逐 stage：ActionSpec → ActionRunner（Docker 沙箱 / 宿主）
  → 阶段结果、日志尾部、产物 → RunHistoryStore + RunLedger
  → 失败：自动修复闭环（确定性修复 → 平台复核 → 续跑），兜底诊断 Agent
```

### 4.4 项目接入链路

```text
POST /api/v1/projects/intake/local|remote
  → ProjectIntakeService（异步虚拟线程）
  → VALIDATING → LINKING → RECIPE_CHECK → REGISTERED / INTAKE_FAILED
  → 生成平台侧 ci 定义（RecipeScaffolder），不写用户仓库
```

## 5. 线程与状态模型

- **不依赖 ThreadLocal**：工具上下文经 AgentScope `RuntimeContext` 显式传递，事件流可在线程间安全流转。
- **虚拟线程**：Spring `spring.threads.virtual.enabled=true`；流水线 worker、BugFix 执行器、DAG 调度均使用虚拟线程。
- **会话状态按 `(userId, sessionId)` 落盘**：AES-256-GCM 加密，目录 700 / 文件 600；裁剪返回新对象，不就地改运行中的 Agent。
- **单机默认、可多实例**：对话并发闸门可用 `jdbc` 模式（SQLite 租约），确认令牌与变更单落统一库；流水线同项目同环境由 `TriggerScheduler` 串行。

## 6. 关键架构决策

| 决策 | 原因 |
|---|---|
| 生产用只读 Toolkit，而非"隐藏工具" | 写工具不在 schema 里，比提示词约束更强 |
| 非生产权限模式用 `BYPASS` 而非 `ACCEPT_EDITS` | 对话链路没有通用工具审批回传通道，ASK 会永久挂起 |
| 确认令牌绑定 `planHash` | 防止"确认 A、执行 B"；配方改动后令牌自动失效 |
| 流水线编排放在仓库 `ci/pipeline.yml` | 模型只能触发已定义流水线，不能拼 stage |
| 环境策略未知环境按 `prod` 处理 | fail-closed |
| 沙箱不注入凭据 | 降低构建期泄露面；需要时挂只读 `settings.xml` |
| 工具按插件渐进披露 | 避免 90+ 工具 schema 每轮进入上下文，节省 token |

## 7. 已知边界

- `confirm` 工具只登记变更，不签发令牌；令牌只能由 HTTP 端点签发。
- 用户体系默认落 SQLite，无成员管理接口，多租户未实现。
- `GET /api/v1/fs/read` 未实现，前端文件预览走其他端点。
- AgentScope 事件映射使用反射 + 稳定 wire type，未收敛为完整枚举。
- 沙箱不注入私服凭据；需要时手工挂载。
