# ruoyi-ai 编程助手拆解
## 怎么写 / 工作区怎么调 / 仓库怎么"导入"

> 日期：2026-11-07 ｜ 对象：`E:\CODE\Agent\ruoyi-ai`（ageerle/ruoyi-ai，v3.1.0 线）
> 目的：为自建 DevOps Agent 平台提供参考实现与可抄/不可抄清单

---

## 0. 三个问题，三句话答案

| 你的问题 | 答案 |
|---|---|
| **编程助手怎么写的** | **两套并存**：老版 `/coding/chat` 是 LangChain4j `AiServices` 声明式（接口 + 6 个工具 + 事件通道）；新版 `/coding/harness` 是**手写的生产级 harness**（254 个类 / 2.95 万行：自研 run loop、工具协议校验、审批、计划、日志、上下文压缩、崩溃恢复），底层仍用 LangChain4j 的 ChatModel |
| **工作区怎么调用的** | `CodingWorkspaceService.resolveHarnessRoot(owner, requestedPath)` 两种模式：**owner 隔离**（`owner-root/t_<tenantHash>/<userId>`，自动创建）或 **shared 白名单**（`shared-root` 边界内的**已存在目录**）；路径全程 `startsWith` + `toRealPath()` 双重校验防逃逸 |
| **怎么允许导入代码仓库** | **没有"导入"这个功能**。真相是：**运维预先把仓库克隆到宿主目录 → 用户在 `shared-root` 内选目录 → `WorkspaceManifest` 声明 1..32 个子项目 → 校验目录真实存在**。JGit 只用来读 status/diff，**不做 clone** |

---

## 1. 两条路线的量级对比（这是最值钱的一行数据）

| | 老版 `/coding/chat` | 新版 `/coding/harness` |
|---|---|---|
| 代码量 | **6 个类 / 477 行** | **254 个类 / 29,550 行** |
| 模型接入 | LangChain4j `AiServices` | LangChain4j `ChatModel`（低层） |
| 工具 | 6 个（read/edit/write/delete/list/exec） | 内置 + 命令 + 计划 + 技能 + 产物，分组与策略 |
| 审批 | ❌ 无 | ✅ 策略引擎 + 审批单 + 过期监控 |
| 计划模式 | ❌ 无 | ✅ Plan 聚合 + 哈希锁定 + 批准/修订 |
| 崩溃恢复 | ❌ 无 | ✅ 启动恢复 + 工具效应账本 |
| 持久化 | 无（内存） | 文件存储 + 事件 outbox + 运行日志 |
| 上下文管理 | 无 | ✅ 预算估算 + 压缩 + 摘要 |
| 适用 | demo / 个人 | 多用户、可审计、可恢复 |

**结论**：`AiServices` 起手 20 行就能跑，但**从"能跑"到"敢给团队用"中间隔着 2.9 万行**——这 2.9 万行买的不是功能，是**审批、恢复、审计、上下文**这四样"不出事"的东西。

### 1.1 老版长什么样（20 行，看完全貌）

```java
ChatModel chatModel = chatService.buildChatModel(modelVo);
CodingAgent agent = AiServices.builder(CodingAgent.class)
        .chatModel(chatModel)
        .tools(read, edit, list, write, delete, exec)   // 6 个工具实例
        .build();
```
```java
public interface CodingAgent {
    @SystemMessage("""你是编程助手，只操作 workspace 内文件……""")
    String chat(@UserMessage String userMessage);
}
```

工具内部把 add/edit/delete/cmd **事件推进 `CodingEventChannel`**，再由外层转成 SSE 增量——这是它做"流式"的全部技巧。

> ⚠️ 注意：这版 `execute_command` 有**输入级防护**，但没有**隔离级防护**。
> 有的：`ProcessBuilder(List)` 不走 shell、元字符黑名单 `` &|;`$<>\n ``、工作目录锁定、30s 超时、输出只回 8KB 尾部。
> 没有的：容器隔离、审批、CPU/内存/pids 限额、以及——**黑名单挡不住 `rm -rf /`**（绝对路径不含任何元字符，直接通过）。
> 这正是"输入校验"与"隔离"的分界：前者防注入，后者防后果。**别把它当生产参考。**

---

## 2. Harness 骨架：按层拆（重点看这层）

### 2.1 对外 API（`CodingHarnessController`，`/coding/harness`）

```
POST   /sessions                     建会话（绑 workspace + manifest + 模型 + 权限模式）
GET    /sessions/{id}/messages       历史
POST   /sessions/{id}/runs           发起一次运行
GET    /sessions/{id}/runs/{rid}/events/stream   SSE 事件流
POST   /sessions/{id}/runs/{rid}/inputs          运行中插话（队列）
POST   /sessions/{id}/runs/{rid}/cancel | resume 取消/恢复
POST   /sessions/{id}/runs/{rid}/approvals/{aid}/resolve   审批
POST   /sessions/{id}/runs/{rid}/plan/approve | plan/revision  计划批准/修订
```
这套接口形状**和 AgentScope 的会话/事件/审批模型几乎一一对应**（见 §6）。

### 2.2 运行内核

| 类 | 作用 |
|---|---|
| `HarnessScheduler` + `HarnessSchedulerCapacityException` | 有界并发，满了**明确拒绝**而不是排队到 OOM |
| `HarnessActiveTurnRegistry` + `HarnessSessionGate` | **一个会话同时只允许一个活跃 turn**（防并发写同一工作区） |
| `DurableHarnessRunProcessor` | 可持久化的 run 主循环 |
| `HarnessRunProcessor` / `HarnessRunRequest` | 单次运行抽象 |
| `ProviderFailureClassifier` / `ModelTurnFailureKind` | 把不同 provider 的错误归一化（限流/超长/内容拒绝） |
| `ProviderOverflowRecovery` | 上下文溢出分级补救（压缩 → 降级 → 失败） |

### 2.3 工具层的三级治理（**最值得抄**）

```
① 协议层  ToolProtocolValidator → ToolProtocolViolationCode
   模型输出的工具调用先校验合法性（未知工具、参数不全、批内冲突），
   不合格不是"重试"，而是产出 SyntheticToolResult 让模型自我纠正。

② 策略层  ToolPolicyEngine + ToolCapability + HarnessPermissionMode
   READ_ONLY / WORKSPACE_WRITE / FULL_ACCESS 三档 + 逐工具能力位；
   高危动作 → HarnessApproval（ON_REQUEST / NEVER），带过期监控
   （HarnessApprovalExpiryMonitor）与"批准后参数被改"检测
   （ApprovalArgumentsMismatchException ← 这个细节非常硬核）。

③ 执行层  HarnessToolBatchExecutor + ToolExecutionGroup + BoundedOutputCollector
   批量并行、组内取消、单个工具超时、输出截断（防一次 cat 大文件撑爆上下文）。
```

**`ApprovalArgumentsMismatchException` 值得单独说**：批准的是「工具 A + 参数 P」，执行时必须校验实际参数仍等于 P。否则模型可以在批准后偷偷改参数——这是审批系统最容易被忽略的一个洞。

### 2.4 计划模式

`PlanAggregate` + `PlanReviewState` + `CanonicalPlanHasher` + `PlanApprovalCommand`：

- 模型先产出结构化计划（步骤 + 验收标准 `AcceptanceCriterion`）；
- 计划被**规范化后哈希**（`CanonicalPlanHasher`）；
- 人批准后，执行阶段校验哈希未变（`StalePlanApprovalException`）；
- 执行产出 `ExecutionEvidence`，按步骤落状态。

> 这套"**计划 = 可哈希的契约**"的设计，比"让模型边说边做"可信一个量级。

### 2.5 持久化与恢复（最硬核的一块）

| 类 | 作用 |
|---|---|
| `FileHarnessStore`（`./data/coding-harness`） | **文件 JSON 存储**，会话/运行/消息都落盘 |
| `RunJournalProjector` + `FileRunJournalProjector` | 把运行过程投影成可回放日志 |
| `JournalSecretRedactor` | 日志脱敏（密钥/token 不落盘） |
| `HarnessEventOutboxService` + `HarnessEventOutboxEntry` | **事件 outbox**：SSE 断线可重放，不丢事件 |
| `HarnessStartupRecovery` | 启动时扫描未完成 run |
| `ToolEffectLedgerReconciler` + `UncertainToolEffectGuard` + `UncertainToolEffectReason` | **处理"工具可能已经执行了但没记账"**——崩溃恢复里最难的一类问题 |

> `UncertainToolEffectGuard` 是我在这个项目里看到最有价值的一个类名。大多数自研 agent 框架都假设"工具调用失败=没发生"，但在 `docker run` / `git push` / `ALTER TABLE` 这些动作上，这个假设是错的。**它显式建模了"不确定态"并要求人工确认。**

### 2.6 上下文与提示

| 类 | 作用 |
|---|---|
| `ContextEngine` + `ContextWindow` + `ContextTokenBudget` + `TokenEstimator` | 预算管理 |
| `CompactionRequest` + `ExtractiveHarnessSummarizer` + `ContextPins` | 超窗压缩 + 钉住关键上下文 |
| `DefaultHarnessPromptAssembler` + `HarnessPromptContext` | 分层拼装系统提示 |
| `ProjectInstructionLoader` | **按优先级读项目指令文件**：`AGENTS.override.md` → `AGENTS.md` → `CLAUDE.md` → `.github/copilot-instructions.md`（128KB 上限，且必须在工作区内） |

> 这最后一条说明一个趋势：**"项目约定"正在成为标准资产**（AGENTS.md 已是事实标准）。你们 RuoYi-Vue-Plus 仓库里已有 `.codex/skills` + `AGENTS.md`，正好接得上。

### 2.7 技能（Skills）

`HarnessSkillCatalog` + `HarnessSkillTools` + `resources/coding-harness/skills/`：

```
skills/repository-investigation/SKILL.md   改代码前先建立证据地图
skills/safe-refactoring/SKILL.md           小步、可验证的重构纪律
skills/verification/SKILL.md               如何证明改对了
```

`repository-investigation` 的核心纪律（摘录）：

> 优先精确检索与行号范围，而不是递归加载整个源码树；
> 区分"接口/文档声称的"与"生产调用点实际用的"；
> 异步行为要找到生命周期归属、队列语义、取消、重连与恢复；
> 安全相关要沿着"认证身份 → 资源 → 副作用"整条链追下去。

**这三份 SKILL.md 可以直接搬到你自己的平台**——与具体技术栈无关，是通用的工程方法论。

### 2.8 命令沙箱（`DockerSandboxConfig`，安全设计的范本）

```java
public record DockerSandboxConfig(
    String dockerExecutable,          // 可执行文件路径
    String dockerExecutableSha256,    // ★ 二进制也要校验哈希
    String dockerConfigDirectory,
    String dockerHost,
    String pinnedImage,               // ★ 必须匹配 sha256:[0-9a-f]{64}（按 digest 钉死，不用 tag）
    int pidsLimit,                    // ★ 256
    long memoryBytes,                 // ★ 1GB
    int cpuMilliCores,                // ★ 2000m
    long tmpfsBytes,                  // ★ 64MB
    String user                       // ★ "65532:65532"（非 root）
) {}
```

外加：**校验 docker socket 的 ACL/POSIX 权限**（`MUTATING_ACL_PERMISSIONS` 检查），确保不是所有人可写。Windows npipe 与 unix socket 都支持。

> 这一条是整个仓库里"最不像 demo"的地方。**镜像按 digest 钉死 + 非 root + 资源限额 + socket 权限校验**，四件套齐全。

---

## 3. 工作区：三个目录、四道校验

### 3.1 三种目录来源

| 来源 | 配置 | 行为 |
|---|---|---|
| **owner 模式**（默认） | `coding.harness.workspace.owner-root: ./data/coding-workspaces` | 自动创建 `owner-root/t_<sha256(tenantId)前12>/<userId>`；请求路径必须**等于**该目录 |
| **shared 模式** | `coding.harness.workspace.shared-root`（默认空） | 用户在该边界内选一个**已存在目录**；`shared-root` 为空则此模式不可用 |
| **遗留硬编码** | `DEFAULT_WORKSPACE = "D:/Project/github/ruoyi-copilot"` | ⚠️ 老版 `/coding/workspace` 只认这一个绝对路径，其他人一律抛异常——**demo 残留，别抄** |

### 3.2 四道路径校验（这是安全核心）

```
① resolveHarnessRoot
   requested.startsWith(boundary)            字面校验
   → boundary.toRealPath() / requested.toRealPath()   解软链后重校验
   → Files.isDirectory(requested, NOFOLLOW_LINKS)     拒绝指向目录的软链

② WorkspaceManifestValidator.normalize
   projectId 正则 [A-Za-z0-9][A-Za-z0-9._-]{0,63}
   relativePath 归一化后必须仍在 leaseRoot 内
   必须是已存在目录（NOFOLLOW_LINKS）
   projectId 与 最终路径都要去重（大小写不敏感）
   → 解软链后再校验一次，产出规范化的 "./xxx" 形式

③ WorkspaceGuard.isWithinWorkspace(root, target)   文件级
④ WorkspaceLeaseGuard（工具执行期租约内）
```

### 3.3 `WorkspaceManifest`：一个工作区装 1..32 个项目

```java
record WorkspaceManifest(int schemaVersion, List<WorkspaceManifestProject> projects)
record WorkspaceManifestProject(String projectId, String relativePath,
                                String displayName, List<String> tags)
```

- `MAX_PROJECTS = 32`；
- **`@JsonAnySetter` 抛异常**：未知字段一律拒绝，注释写得很直白——"未知字段不能用来偷渡授权契约"（比如有人塞 `writable: true`）；
- 兼容老会话：不传 manifest 时退化为单项目 `.` + tag `primary`。

> **这是"多项目工作区"的最小可行模型**：不是多租户，是"一个工作区里有多个子项目"。你们 v4 的多项目托管可以直接借这个形状——但要把它从"目录描述"升级成"项目实体"（带环境、凭证、配额）。

---

## 4. 「导入代码仓库」的真相与你要补的

### 4.1 它到底怎么"导入"的

```
运维：git clone <repo>  → 放到 /srv/workspaces/<xxx>   （平台之外，人工/脚本）
       ↓
配置：coding.harness.workspace.shared-root=/srv/workspaces
       ↓
用户：创建会话时选该目录 → 可再声明 manifest（1..32 个子目录当"项目"）
       ↓
校验：必须是已存在目录、必须在边界内、软链不可逃逸
       ↓
Agent：在 guard 保护下 read/write/search/git status/diff/exec
```

**没有 clone 的原语，没有"填 Git URL 导入"的接口，没有凭证管理。** 这是一个明确的**产品空白**——对 ruoyi-ai 来说是"够用"，对你要做的多项目平台来说是**必须补**。

### 4.2 你要补的六件事

| # | 能力 | 要点 |
|---|---|---|
| 1 | `RepoProvisioner`（clone） | `--depth 1 --single-branch`；目标 `<shared-root>/<projectKey>`；失败可重试、可清理 |
| 2 | 凭证 | 按项目取 token；**不进命令行参数**（会出现在 `ps`/日志），用 `GIT_ASKPASS` 或 credential helper |
| 3 | 协议白名单 | **禁止 `ext::` / `file://`**（`ext::` 可导致任意命令执行）；只允许 https/ssh 且域名白名单 |
| 4 | 并发与幂等 | 同 repo 只 clone 一次；后续用 `git fetch` 增量；同一 worktree 加锁 |
| 5 | 每任务隔离 | 建议 **bare 仓库 + `git worktree`**，每任务一个工作树，避免多任务改同一目录 |
| 6 | 自动生成 manifest | clone 后扫描子仓库/模块，生成 `WorkspaceManifest`，别让用户手填 |

> JGit 能做 clone，但要自己处理凭证与 `.gitconfig` 隔离；ruoyi-ai 用 JGit 只做**只读** status/diff，这个边界划得很清楚，值得学。

---

## 5. 十抄五不抄

### ✅ 值得抄（按价值排序）

| # | 做法 | 为什么 |
|---|---|---|
| 1 | **`UncertainToolEffectGuard`**：显式建模"工具可能执行了" | 崩溃恢复里唯一的正确姿势；`git push`/`docker run`/DDL 上尤其重要 |
| 2 | **计划哈希（`CanonicalPlanHasher`）** | 审批后不可偷改计划 |
| 3 | **审批参数一致性校验（`ApprovalArgumentsMismatchException`）** | 防"批准 A，执行 B" |
| 4 | **manifest 未知字段 fail-closed** | 防塞权限字段提权 |
| 5 | **Docker 沙箱四件套**：digest 钉镜像 + 非 root + 资源限额 + socket ACL 校验 | 直接可用的生产基线 |
| 6 | **项目指令优先级链**（AGENTS.override.md → AGENTS.md → CLAUDE.md → copilot-instructions.md） | 标准化，且你们仓库已有 AGENTS.md |
| 7 | **三份通用 SKILL.md**（调查/安全重构/验证） | 与栈无关，可直接搬 |
| 8 | **工具协议校验 + SyntheticToolResult** | 比"报错重试"更省 token，也更稳 |
| 9 | **事件 outbox**（SSE 断线可重放） | 前端体验与审计都受益 |
| 10 | **`BoundedOutputCollector` 输出截断** | 一条 `cat bigfile` 就能毁掉上下文 |

### ❌ 不要抄

| # | 做法 | 问题 |
|---|---|---|
| 1 | `FileHarnessStore` 文件存储 | 单机可行；多实例/多项目会散乱，应用 DB + 对象存储 |
| 2 | 硬编码 `DEFAULT_WORKSPACE` | demo 残留，多环境必炸 |
| 3 | 用宿主目录当工作区（不 clone） | 你无法做"用户自助导入" |
| 4 | 老版 `/coding/chat` 的命令执行模型 | 黑名单式防护挡不住 `rm -rf /`（无元字符即放行）；缺容器隔离与审批。要抄的是它的**输出截断 + 超时**，不是它的防护策略 |
| 5 | 手写整个 harness | 见 §6：3 万行里约 70% 是 AgentScope 现成的 |

---

## 6. 与 AgentScope 的能力对照（决定你写多少）

| ruoyi-ai 自研 | AgentScope 现成 | 你要写的 |
|---|---|---|
| `DurableHarnessRunProcessor` + run loop | `ReActAgent` / `HarnessAgent` 主循环 | 业务编排、状态落库 |
| `ToolProtocolValidator` | formatter + provider 适配层 | 自定义校验规则（如需） |
| `ToolPolicyEngine` + `HarnessApproval` | `PermissionRule`(ALLOW/ASK/DENY) + `ConfirmResult` | 策略来源（项目/角色） |
| `PlanAggregate` + 计划批准 | Plan mode（`docs/v2/.../plan-mode`） | 验收标准与证据结构 |
| `ContextEngine` + 压缩 | 上下文压缩（harness context） | 预算策略调参 |
| `FileHarnessStore` + 恢复 | `AgentStateStore`(MySQL/Redis) + `SessionLog` | 会话日志落库（SessionLogStore 需自写） |
| `WorkspaceGuard` | `Workspace` | clone/worktree 供给 |
| `DockerSandboxConfig` | `Sandbox`（Docker/K8s 后端） | 沙箱镜像与配额策略 |
| `HarnessSkillCatalog` | `FileSystemSkillRepository` | SKILL.md 内容（可直接搬） |
| 事件/SSE | 32 类 `AgentEvent` | 前端渲染 |
| — | — | **项目/环境/凭证（v4）**、**Issue 桥**、**部署控制器**、**迁移治理** |

**量化结论**：ruoyi-ai 用 2.95 万行买到的东西，AgentScope 覆盖约 **70%**。你真正要自己写的，正是前面几篇文档里那四块**业务性**能力——这也反过来验证了那条路线是合理的。

---

## 7. 一句话总结

> ruoyi-ai 的编程助手是**「两个人用 2.9 万行把一个 agent 该有的治理补齐了」**的样本：它能跑、能审、能恢复、能审计，但**工作区是运维手工准备的目录、没有仓库导入、没有多项目环境**。
> 对你而言：**它的"治理层"值得抄（尤其是不确定态、计划哈希、沙箱四件套），它的"供给层"必须重写（clone/worktree/多项目/凭证），而它的"运行时"大部分可以交给 AgentScope。**
