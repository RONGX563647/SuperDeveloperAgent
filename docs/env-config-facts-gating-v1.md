---
type: reference
summary: "环境配置事实层与分环境门控"
code_refs:
  - platform/agent-governance/src/main/java/top/rongx/agent/governance
---
# 环境配置事实层与分环境门控 · 方案 v1

> 状态：**现行部分 + 规划部分（2026-11-15 按代码核对）**。已落地：密钥引用、策略矩阵、dry-run/门控、配置扫描与主动询问、密钥库 UI、日志脱敏。未落地：`config_fact_drafts` 审核表、`policy_observations`/observe、`SecretStore` 端口、按项目类型的定制事实提取。逐项状态见 §6.1；§6 的 P0–P2 是原始计划，不等于现状。
> 目标：解决两个痛点——「配置散落在文档/消息里，Agent 对不上环境」与「约束太死跑不通、放开又不放心」。
> 结论：**不引入新的大平台**，在现有 `EnvironmentStore` / `EnvBinding` / `ActionService` / `PipelineEngine` 上演进三件事：
> 1. 配置事实层：提取 → 人工确认 → 固化，Agent 只读事实，不现场猜；
> 2. 密钥引用：配置只存 `secret://` 引用，执行最后一刻解析，Agent 全程不见明文；
> 3. 分环境策略 + 阶段门控：dev/test 给高自由度，prod 收紧到「阶段产物审批」，不再逐命令卡。

---

## 1. 现状盘点：已经有的，不再造

| 能力 | 现状 | 代码位置 | 判断 |
|---|---|---|---|
| 环境事实存储 | `env_templates` / `env_vars` / `project_env_overrides`，按「项目 × 环境」组织；敏感值 AES-GCM 落库、接口只回掩码 | `SqliteEnvironmentStore`、`EnvVariable` | **保留**，就是最小事实层 |
| 配置声明扫描 | 已扫 `.env` / `.env.example` / `entrypoint.sh require` / compose `${VAR:?}` / application 配置 | `ProjectEnvFiles` | **扩展**，不要重写 |
| 确认闭环 | `env_mapping_draft` 生成 DRAFT → 人工在对话内确认卡确认（Agent 不能自批）→ 24h 有效 → `mappingHash` 防确认后变更（E-703） | `EnvBindingService`、`EnvQueryTools` | **复用**，事实确认与部署绑定共用同一套确认语义 |
| 部署快照 | 每次部署记 `env-snapshot` 产物，只存 key + 值哈希，不落明文 | `BuiltInPipelineEngine` | **保留**，密钥化后改为存引用版本哈希 |
| 执行门禁 | `ActionId` 固定 7 个动作；L2 仅非生产直跑；L3 必须 `request → confirm → apply`，令牌一次性、5 分钟、绑 `planHash` | `ActionService`、`ChangeController`、`ConfirmTokenService` | **保留**，把散落判断收口到策略层 |
| 环境工具面 | prod 只装配只读 Toolkit；非生产装配完整 Toolkit | `ToolCatalog`、`AgentFactory` | **保留**，由策略层统一决策 |
| 流水线预览 | 已有 `preview`：解析配方、生成命令脚本、不执行 | `BuiltInPipelineEngine#preview` | **升级为 dry-run** 的基础 |
| 技能固化 | 环境跑通后固化 `recipes/<env>/vN/`，可重放/回滚 | `FileRecipeStore` | **复用**，补事实摘要字段即可 |

**真正缺的是四件事**：

1. 非结构化来源（README、docs、群聊/工单文本）没有提取入口，只有 `.env.example`；
2. 配置里只能存「值」或「加密后的值」，没有**引用**，密钥无法轮换、无法跨环境复用；
3. 环境自由度是**二元**的（prod 只读 / 非 prod 全量），没有 dev/test 的差异化策略；
4. 约束仍在「单动作/单命令」层，没有「dry-run 通过才允许 apply」的**阶段门控**。

---

## 2. 目标结构

```
非结构化来源                 事实层                        执行层
README/docs/消息  ──提取──▶  ConfigFactDraft ──人工确认──▶  env_templates / overrides
                                                              │
                                                              ├─ 变量值（明文/掩码）
                                                              └─ secret://ref ──▶ 环境存储（SecretBox 解密）
                                                                                    │
Agent 规划（高自由度）──▶ DryRunReport ──阶段门控──▶ Apply ──▶ 部署器解析密钥并注入
                                      │
                                      └─ prod：人确认阶段产物（planHash；dryRunHash 仅记录）
```

> 图中 `ConfigFactDraft` 是**规划**（§3.1 未建表）；现行只有“扫描候选 → 人工一键采纳为项目覆盖”。

四条不变量：

- **事实先固化，再执行**：Agent 不在部署现场读文档推断配置；
- **密钥只存引用**：配置模板、草案、日志、模型上下文都不出现明文；
- **规划自由，执行受控**：Agent 可以生成计划/脚本/补丁，但不能绕过阶段门控触碰真实系统；
- **策略按环境，不按命令堆白名单**：门禁卡在阶段转换，不卡每条命令。

---

## 3. 方案 A：配置事实层（extract → confirm → freeze）

### 3.1 数据模型（规划，未实现）

> `config_fact_drafts` 未建表；现行没有事实草案审核页。配置扫描的“候选”是内存/接口即时结果，可一键写入项目覆盖，见 §3.2 末与 §6.1。

新增 `config_fact_drafts`（SQLite，跟 `projects.db` 同库）：

| 字段 | 说明 |
|---|---|
| `id` | 草案 id |
| `project_id` / `env_hint` | 项目；环境提示（dev/test/prod/unknown） |
| `key` | 变量名或配置项名 |
| `value_kind` | `PLAIN` / `SECRET_REF` / `SECRET_DETECTED` / `MISSING` |
| `value_cipher` / `iv` | 仅当用户在确认时选择「新建密钥」才写入；**提取阶段不落明文** |
| `value_hash` | 用于冲突检测与部署快照，不存原值 |
| `source_type` | `ENV_FILE` / `COMPOSE` / `APP_CONFIG` / `DOC` / `CHAT` / `TICKET` |
| `source_ref` | 文件路径 + 行号，或消息 id |
| `excerpt` | 脱敏后的上下文片段（≤200 字符） |
| `confidence` | 规则命中为 `HIGH`，LLM 推断为 `MEDIUM/LOW` |
| `status` | `CANDIDATE` / `CONFLICT` / `CONFIRMED` / `REJECTED` |
| `confirmed_by` / `confirmed_at` | 人工确认信息 |

确认后不直接改模板，而是写入 `project_env_overrides`（来源标记 `FACT`），并保留 `source_ref` 以便追溯。模板（全局）只允许管理员在设置页改。

### 3.2 提取：规则优先，LLM 只做候选

分两层，避免「一次读偏就部署错环境」：

**第一层（必做，确定性）**

- 复用 `ProjectEnvFiles` 的文件遍历与 `.env` 解析；
- 新增扫描：`README*.md`、`docs/**/*.md`、`docker-compose*.yml`、`application*.yml/properties`、`Dockerfile`、`ci/**.yml`；
- 规则：
  - `KEY=VALUE` / `KEY: VALUE`；
  - `host:port`、`jdbc:`、`redis://`、`amqp://`、`http(s)://`；
  - `image: name:tag`（版本）；
  - 文档标题/段落里的 `dev` / `test` / `prod` 作为 `env_hint`；
- 每条候选必须带 `source_ref`，否则不进入草案（**现行** `ConfigResolver.Candidate.source` 只有文件相对路径，**没有行号**；行号属于规划）。

**第二层（可选，LLM 辅助 —— 规划，未实现）**

> 现行 `ConfigResolver` 只有确定性扫描（`.env*`、`compose`、`application*`、Dockerfile、文档中的 `KEY=VALUE` 与 `image:`），没有调用模型提取自然语言。

- 仅对规则没覆盖的自然语言片段调用模型；
- 输出必须是结构化候选列表，且**每条必须引用原文片段**；
- 置信度低于阈值只展示、不参与「缺失变量」计算；
- 模型不能直接写事实，只能生成 `CANDIDATE` 草案。

**密钥处理**

- 扫描到疑似密钥（`PASSWORD` / `SECRET` / `TOKEN` / `_KEY` / 高熵字符串）时：
  - 不保存明文；
  - 标记 `SECRET_DETECTED`，提示「疑似明文密钥，建议轮换并改为引用」；
  - 确认时默认引导用户创建 `secret://` 引用，而不是把值存进环境变量。

### 3.3 确认与固化（规划，未实现）

> 现行设置页“项目环境”支持直接编辑覆盖项；没有“配置事实”审核页，也没有 `FACT` 来源标记。

- 列表按 `key` 聚合多来源，展示：当前有效值（掩码）、候选值哈希、来源文件/行、冲突标记；
- 冲突（同一 key 多个非空值）不允许一键确认，必须逐条选择或标记忽略；
- 确认动作需要用户权限（`settings:write`），并写审计；
- 确认后：
  - 写入 `project_env_overrides`，`source=FACT`；
  - 草案状态置 `CONFIRMED`；
  - 触发一次 `effectiveHash` 重算；已有部署绑定因摘要变化自动失效（复用 E-703）。

### 3.4 复用与固化（部分规划）

- `env_summary` 增加 `facts: confirmed / draft / conflicts`、`lastConfirmedAt`、`sources`：**未实现**；现行 `env_summary` 只回变量计数/来源分类/缺失项，不回值；
- `env_mapping_draft` 继续作为“本次部署使用哪一版事实”的绑定；
- `FileRecipeStore` 的 `meta.yml` 增加 `factsDigest` / `envFingerprint` / `dryRunHash`：**未实现**；现行只在 recipe 存运行元数据；
- `deploy_env_status` 返回上述摘要：**未实现**；现行对应能力是 `GET /api/v1/settings/projects/{id}/env-check`。

### 3.5 接口与工具（规划，未实现）

> 现行没有 `/env-facts/*` 路由，也没有 `config_facts_extract` / `config_facts_summary` 工具；现有入口是 `GET /api/v1/projects/{projectId}/config/resolve`（`ConfigController`）与设置页“采纳为项目覆盖”。

| 类型 | 名称 | 级别 | 说明 |
|---|---|---|---|
| HTTP | `POST /api/v1/projects/{id}/env-facts/extract` | 用户 | 从仓库文件/指定消息提取候选 |
| HTTP | `GET /api/v1/projects/{id}/env-facts?env=` | 用户 | 查看候选/冲突/已确认 |
| HTTP | `POST /api/v1/projects/{id}/env-facts/{draftId}/confirm` | 用户 | 人工确认并固化 |
| HTTP | `POST /api/v1/projects/{id}/env-facts/{draftId}/reject` | 用户 | 拒绝候选 |
| Tool | `config_facts_extract` | L1 | 只生成草案，不确认、不写环境 |
| Tool | `config_facts_summary` | L0 | 只回数量/冲突/来源，不回值 |

**Agent 没有 `confirm` 工具**，确认只能由人经 HTTP 完成，延续现有不变量。

---

## 4. 方案 B：密钥引用（本次优先落地）

### 4.1 目标

配置模板只写引用，真实值在执行最后一刻由密钥库解析；模型、接口、日志、绑定记录全程看不到明文。

### 4.2 数据模型（现行）

现行 `secrets` 表（`SqliteEnvironmentStore` 建表）：

| 字段 | 实际说明 |
|---|---|
| `id` | 密钥 id（`secret-<uuid>`） |
| `project_id` | 项目；**非空**，不支持“全局密钥” |
| `env` | dev/test/prod；**非空**，不支持“项目内共享” |
| `name` | 引用名，如 `db-password`，`UNIQUE(project_id, env, name)` |
| `value_enc` / `value_iv` | 复用 `SecretBox` 的 AES-256-GCM（字段名不是 `cipher`/`iv`） |
| `version` | 每次轮换 +1 |
| `created_at` / `rotated_at` | 审计 |

`env_vars` / `project_env_overrides` 增加：

- `value_kind`：`INLINE`（现状，加密值）/ `SECRET_REF`；
- `secret_ref`：`secret://db-password` 或 `${secret:db-password}`；
- 现有数据迁移为 `INLINE`，不改语义。

引用语法：

- 整值引用：`DB_PASSWORD=secret://db-password`；
- 嵌入引用：`DATABASE_URL=jdbc:mysql://host:3306/app?password=${secret:db-password}`；
- 解析失败必须 fail-closed，不允许把占位符原样注入。

### 4.3 解析与注入（现行实现）

- **没有新增 domain 端口 `SecretStore` / `SqliteSecretStore`**；密钥的加密、轮换、解析都在 `SqliteEnvironmentStore` 内（复用 `agent-infra` 的 `SecretBox`），引用语法解析在 `agent-domain` 的 `SecretRefs`；
- `EffectiveEnvironment` 没有 `resolvedValues()`：`vars()` 是掩码/`secret://name` 视图，`values()` 是含已解析密钥的明文，仅供执行面；`digestValues()` 用于绑定/快照，引用变量输出 `ref:<name>@<version>`；
- 解析发生在 `EnvironmentStore.effective(projectId, env)` 内（整值 `secret://` + 嵌入 `${secret:name}`）；解析失败不抛异常，而是记入 `missingSecrets`、变量置空；
- **部署前预检**（`BuiltInPipelineEngine` 的 dry-run 与 deploy stage）据此 fail-closed：
  - 引用不存在 / 无法解析 → `E-704`；
  - 密钥轮换导致版本变化 → 摘要变化，已确认绑定校验失败 → `E-703`；
  - 未解析成功不启动容器、不执行 apply。
- 另：`E-509` 是“缺少必填环境变量”的预检错误，与密钥引用共用同一预检入口，但含义不同。

### 4.4 掩码与日志脱敏

- 所有 HTTP 响应继续只回 `****` + 末 4 位（`SecretBox.mask`）；
- `env_mapping_draft` / `env-snapshot` 使用 `digestValues()`：引用变量存“引用名 + 密钥版本”的哈希，不再需要明文；
- `SecretRedactor` 位于 `agent-tools`（不是 `agent-infra`），在以下位置替换成本次运行的密钥明文：
  - `ProcessExecutor.redact(text, values)` 的行输出；
  - `DockerDeployer` / `HostDeployer` / `ServerDeployer` 的日志摘要；
  - `AppRuntimeInspector` 与 `app_logs` 的 tail；
  - 构建日志的 `ERROR` 摘要；
- 同时替换 URL 编码/JSON 转义后的变体，避免只替换原始串漏掉；
- `pipeline preview` 只展示命令，不展示环境变量；新增测试防止回归。

### 4.5 轮换与绑定失效

- 轮换只改 `secrets.version`，不改引用；
- `effectiveHash` / `envFingerprint` 包含 `secretRef@version`；
- 轮换后旧绑定自动失效（**E-703**），必须重新确认；
- 保留上一版密钥 24h 的过渡策略**未实现**，当前轮换即覆盖旧密文。

### 4.6 接口与 UI（现行）

| 类型 | 实际路由 | 说明 |
|---|---|---|
| HTTP | `GET /api/v1/settings/projects/{projectId}/secrets?env=` | 只回名称、版本、掩码、更新时间 |
| HTTP | `POST /api/v1/settings/projects/{projectId}/secrets` | 新建/更新密钥（无独立审计表，仅保留 created_at/rotated_at） |
| HTTP | `POST /api/v1/settings/projects/{projectId}/secrets/{name}/rotate` | 轮换，版本 +1 |
| HTTP | `DELETE /api/v1/settings/projects/{projectId}/secrets/{name}?env=` | 直接删除（**未做引用检查**，被引用后解析会报 E-704） |
| Tool | —— | `secret_list` **未实现**，Agent 无密钥库工具 |

> 不存在 `/api/v1/secrets` 全局路由；密钥始终按 `projectId + env` 隔离。

设置页环境变量编辑支持两种模式（已落地，见 `SecretPanel.vue` / `SettingsView.vue` 的“密钥库”）：

- 「直接填写」：沿用现状，敏感项加密落库；
- 「引用密钥」：下拉选择已有密钥，或就地新建；默认推荐引用。

### 4.7 错误码

| 码 | 实际含义（按代码） |
|---|---|
| `E-704` | 密钥引用不存在或无法解析；**也用于**远端部署端口耗尽（`ServerDeployer`） |
| `E-705` | **不是**密钥轮换错误；实际是本机 Docker 不可用/镜像不存在（部署预检） |
| `E-706` | dry-run 报告哈希与当前配方/环境/密钥版本不一致（过期） |
| `E-707` | 当前环境策略拒绝该操作（`EnvPolicyService`） |
| `E-708` | dry-run 未通过，禁止 apply |
| `E-703` | 环境绑定缺失/已过期/映射哈希不一致（密钥轮换即走此码） |
| `E-509` | 缺少 `deploy.env-required` 声明的必填变量 |
| `E-701` / `E-702` | 中间件启动/就绪失败 / 环境准备失败 |

> 原文的 `E-705 SECRET_ROTATED` 在代码中**不存在**；轮换失效请引用 `E-703`。

---

## 5. 方案 C：分环境策略 + 阶段门控

### 5.1 把散落的环境判断收口

当前环境判断分散在：

- `ActionService.run`：`ctx.isProduction()` 直接拒绝 L2；
- `CiCommandTools.run`：`ctx.isProduction()` 直接拒绝；
- `AgentFactory.create`：prod 只读 Toolkit；
- `DeployTools` / `PipelineEngine`：部署前 binding、E-509、E-701 等。

新增 `EnvPolicy`（domain）与 `EnvPolicyService`（governance），统一回答三个问题：

1. 这个工具/动作在该环境是 `ALLOW` / `CONFIRM` / `DENY`？
2. 该环境是否允许「dry-run 通过后自动 apply」？
3. 该环境是否必须人工确认阶段产物？

默认矩阵（**现行硬编码在 `EnvPolicyService.defaults()`，`application.yml` 不能覆盖**）：

| 环境 | L0 读 | L1 计划 | L2 隔离执行 | L3 生产动作 | dry-run 后自动 apply | 部署绑定 |
|---|---|---|---|---|---|---|
| dev | ALLOW | ALLOW | ALLOW | DENY | 是 | 必须 |
| test | ALLOW | ALLOW | ALLOW | **CONFIRM** | 是 | 必须 |
| prod | ALLOW | ALLOW | DENY | CONFIRM | 否 | 必须 + 人工确认 |

说明：

- `ALLOW` 不等于无约束：仍受 Docker 沙箱（无 Docker 时 fail-closed）、动作白名单、binding、E-509/E-701/E-704 约束；
- prod 的 L3 **禁止配置成 `ALLOW`**，代码里硬性拒绝这种配置；
- **test 的 L3 现行就是 `CONFIRM`**，不存在“流水线内部 ALLOW、Agent 直调 CONFIRM”的分叉；所有 L3 都走 `request → confirm → apply`；
- 未知环境按 prod 处理（fail-closed）；
- dev/test 的“更大自由度”体现在：允许 L2 直跑、允许 dry-run 后自动 apply、允许补齐环境/中间件，但**不允许跳过 dry-run 与绑定**。

### 5.2 Dry-run：把「预览」升级为「可审批的阶段产物」

现有 `preview` 只生成命令脚本，不检查环境；新增 `DryRunReport`（**现行字段**，见 `PipelineEngine.DryRunReport`）：

```
reportHash        报告哈希（审批绑定用）
runFingerprint    project + env + pipeline + gitSha + envFingerprint + 各 stage scriptSha
ok                是否可执行
blockers[]        阻断原因（Docker 不可用、缺必填变量、引用解析失败等）
warnings[]        非阻断提示
stages[]          {id, type, action, runnable, reason, command, scriptSha, builtin}
createdAt         生成时间（毫秒）
```

> §5.2 原稿里的 `preflight{...}`、`rollback{...}`、`expiresAt` **未实现**：预检结果被展开进 `blockers/warnings`，过期用 `createdAt + 配置 TTL` 判断（`E-706`）。

**零 outward 变更**定义：

- 不创建/启动/停止容器；
- 不推送镜像、不写远端服务器；
- 不执行 deploy/rollback；
- 不修改工作区外的文件；只在临时目录生成脚本；
- 只做只读探测：`docker version`、端口占用、目标可达性、文件存在性、`bash -n` / `docker compose config`（仅本地解析）。

默认 dry-run **不跑构建和测试**（最贵且可能产生副作用），只回答「计划是否可执行、环境是否齐备」。需要验证编译时，显式使用现有 `forVerify` 切片跑构建（另开模式）。

### 5.3 阶段门控与审批对象

Apply 前必须满足：

1. `dryRun.ok == true`；
2. `dryRun.runFingerprint == 当前 fingerprint`（代码/配方/环境/密钥版本任一变化即失效）；
3. `envBinding` 已确认且未过期；
4. 当前环境策略允许 apply。

分环境执行：

- **dev/test（AUTO）**：引擎内部先跑 dry-run，通过后自动 apply，报告与 runId 一起落历史；失败可重试/回滚；
- **prod（CONFIRM）**：人查看 `DryRunReport` 后确认，令牌绑定 **`planHash`**；`dryRunHash` 仅记录在 trigger 参数并参与 `runFingerprint`/过期校验，**并未并入确认令牌**。apply 时若报告过期/指纹不一致 → `E-706`。

这正好把「逐命令审批」换成「阶段产物审批」：人确认的是最终脚本、目标清单、环境指纹和回滚方案。

### 5.4 观察模式（规划，未实现）

> `agent.policy.mode`、`policy_observations` 表、`GET /api/v1/policy/observations` 均不存在；现行只有 `enforce`。

- **仅 dev/test 可设 observe**，prod 永远 enforce；
- observe 下，本应 `DENY` / `CONFIRM` 的操作记为 `WOULD_DENY` / `WOULD_CONFIRM`，照常执行；
- 记录到 `policy_observations`：动作、环境、级别、来源、原因、时间；
- 提供 `GET /api/v1/policy/observations` 与「导出策略草案」；
- 人工复核后把高频放行项写进 `EnvPolicy`，避免「拍脑袋定规则把正常流程卡死」。

### 5.5 代码改造点（现状：部分落地）

| 模块 | 实际落地 |
|---|---|
| `agent-domain` | 已实现 `EnvPolicy`、`PolicyDecision`、`DryRunReport`；`SecretRefs`（不是 `SecretRef`）；`EffectiveEnvironment` 未拆视图，`values()` 即明文 |
| `agent-infra` | `SecretBox` + 密钥存取在 `SqliteEnvironmentStore`；**没有** `SqliteSecretStore`/`SecretResolver`/`ConfigFactExtractor`/`SqliteConfigFactStore`/`SecretRedactor` |
| `agent-governance` | `EnvPolicyService` 已实现；`ConfirmTokenService` 仍只绑 `planHash`（未扩三元组） |
| `agent-executor` | `PipelineEngine.dryRun` 已实现；`ProcessExecutor` 已接 `SecretRedactor`；recipe `meta.yml` 未加事实指纹 |
| `agent-tools` | 已有 `config_resolve`、`ci_dry_run`；**没有** `config_facts_extract`/`config_facts_summary`/`secret_list` |
| `agent-runtime` | `AgentFactory` 已按策略选 Toolkit |
| `agent-gateway` | 已有 `/secrets/*`、`/pipelines/{id}/dry-run`；**没有** `/env-facts/*`、`/policies/*` |
| `web` | 已有密钥库、dry-run 预检报告；无事实审核页 |
| `spec-checks` | 未新增 ArchUnit 规则 |

---

## 6. 落地顺序与验收

> **以下 P0/P1/P2 是原始计划与验收口径，不代表全部已完成。** 实际状态见 §6.1；其中 P2 的 `config_fact_drafts`、observe、LLM 提取仍未实现。

### P0：密钥引用 + 策略收口

**交付**

1. `secrets` 表 + `SecretBox` 复用 + `secret://` 解析；
2. `env_vars` / `project_env_overrides` 增加 `value_kind` / `secret_ref`；
3. `SecretRedactor` 接入执行日志与 `app_logs`；
4. `EnvPolicyService` 替换 `ActionService`、`CiCommandTools`、`AgentFactory` 的硬编码环境判断；
5. 配置默认矩阵（dev/test/prod）。

**验收**

- 配置模板只存引用，`env_summary` / `env_mapping_draft` / 日志均无明文；
- 引用不存在时部署前失败（E-704），不启动容器；
- 轮换后旧绑定失效（**E-703**），必须重新确认；
- prod 的 L2 即使 Agent 直接调用也被拒；L3 仍走令牌；
- dev/test 的 L2 行为与现状一致，未因策略收口而收紧。

### P1：Dry-run + 阶段门控

**交付**

1. `DryRunReport` + `POST /pipelines/{id}/dry-run`；
2. `ci_run` 在 dev/test 内部 dry-run → apply；prod 走人工确认；
3. 令牌绑定 `planHash + dryRunHash + envFingerprint`（**规划**；现行仅 planHash，dryRunHash 只参与 trigger 校验）；
4. recipe `meta.yml` 记录 `factsDigest` / `envFingerprint` / `dryRunHash`；
5. 前端 dry-run 报告与审批面板。

**验收**

- dry-run 对容器/远端/部署零变更（测试断言无 deploy 调用）；
- 配方、环境变量、密钥版本任一变化，旧 dry-run/令牌失效（E-706）；
- dev/test 一次触发即可完成 dry-run + apply，不需要逐命令确认；
- prod apply 必须由人确认阶段产物。

### P2：配置事实提取 + 观察模式

**交付**

1. `ConfigFactDraft` + 规则扫描（仓库文档/compose/application）；
2. 设置页事实审核（冲突、来源、确认/拒绝）；
3. LLM 辅助提取（可选、低置信度只展示）；
4. `policy_observations` + observe 模式（仅 dev/test）；
5. 事实摘要进入 `env_summary` / `deploy_env_status`。

**验收**

- 从 README/compose 提取的候选带文件:行号，确认后部署实际生效；
- 冲突不被静默覆盖；
- Agent 只能生成草案，不能确认；
- observe 模式不会在 prod 生效。

---

### 6.1 实现状态（2026-11）

| 条目 | 状态 | 落点 |
|---|---|---|
| `secrets` 表 + AES-GCM + `secret://` / `${secret:}` 解析 | 已完成 | `SecretEntry`/`SecretRefs`、`SqliteEnvironmentStore`、`SecretController` |
| `value_kind` / `secret_ref` 列与迁移 | 已完成 | `SqliteEnvironmentStore.ensureColumn` |
| 缺失引用 fail-closed E-704 | 已完成 | `BuiltInPipelineEngine` 部署阶段 + `dryRun` |
| 轮换版本参与摘要 / 绑定失效 | 已完成（版本进摘要；绑定失效走 E-703，实际校验在 `EnvBindingService`） | `digestValues()`、`EnvBindingService` |
| 策略矩阵替换硬编码环境判断 | 已完成 | `PolicyPort`、`EnvPolicyService`、`ActionService`、`CiCommandTools`、`AgentFactory` |
| `DryRunReport` + `/pipelines/{id}/dry-run` + `ci_dry_run` | 已完成 | `PipelineEngine.dryRun`、`PipelineController`、`CiCommandTools` |
| `ci_run` 内部 dry-run → apply，E-708/E-706 | 已完成 | `CiCommandTools`、`PipelineController` |
| 令牌绑定 `dryRunHash` | 部分（trigger params 记录哈希；确认令牌本身仍绑 `planHash`） | `PipelineController` |
| 前端 dry-run 报告/审批面板 | 已完成预检报告；审批沿用既有确认弹窗 | `TriggerBar.vue` |
| 配置全量扫描 + 优先级 + 主动询问 | 已完成（确定性扫描；无 LLM 辅助） | `ConfigResolver`、`ConfigTools`、`ConfigController` |
| `SecretRedactor` 日志脱敏 | 已完成（形态匹配 + 本次注入密钥值整段替换） | `ProcessExecutor.redact(text, values)`、`RunState.sensitiveValues` |
| `config_fact_drafts` 审核表 / `policy_observations` / observe | 未实现（P2 延后；候选已支持人工一键采纳为项目覆盖） | — |
| `config_facts_extract` / `config_facts_summary` / `secret_list` 工具 | 未实现 | 现有工具为 `config_resolve` |
| 前端密钥库 | 已完成 | `SecretPanel.vue`、`SettingsView.vue` |

---

## 7. 明确不采纳 / 延后

| 方案 | 决定 | 原因 |
|---|---|---|
| 引入 Rhai / Cedar 策略引擎 | 不采纳 | 现有 Docker 沙箱 + 动作白名单 + 策略矩阵足够；新增语言/策略引擎维护成本过高 |
| 建 CMDB | 不采纳 | 「模板 + 项目覆盖 + 事实草案」已能覆盖小团队；CMDB 会引入同步与权限负担 |
| 让 Agent 直接读文档后自动写配置/部署 | 不采纳 | 一次读偏就错环境；必须「草案 + 人工确认」 |
| 命令级白名单继续扩张 | 不采纳 | 复杂部署必然漏；控制点上移到阶段门控与策略层 |
| 配置里存明文密钥 | 不采纳 | 已有 AES-GCM 与掩码，必须继续坚持引用化 |
| prod 开 observe / 自动 apply | 不采纳 | 生产必须人工确认阶段产物 |
| 自动生成 Jenkinsfile / Groovy | 延后 | 与当前「固定动作 + 仓库配方」模型冲突，收益低 |
| 独立 markdown 技能库 | 延后 | 先用 recipe `meta.yml` 沉淀事实摘要与失败经验，避免再造一套知识库 |

---

## 8. 关键风险与对策

| 风险 | 对策 |
|---|---|
| 文档里的配置已过期 | 候选必须带来源与行号；冲突显式展示；确认后才生效 |
| LLM 提取幻觉 | 规则优先；LLM 候选必须引用原文；低置信度不参与缺失判断 |
| 密钥出现在日志/异常 | 统一 `SecretRedactor`；对 URL 编码/JSON 转义变体也替换；预览不输出 env |
| 轮换导致部署失败 | 版本化 + 绑定失效提示；可选保留上一版 24h 过渡 |
| dry-run 与真实执行环境不一致 | 报告标明「宿主探测 / 容器内探测」；apply 前再次校验目标可达性 |
| 策略配置错误把 prod 放开 | 代码硬性禁止 prod L3=AUTO；启动时校验策略矩阵 |
| 事实层再次变成「另一个配置源」 | 事实确认只写 `project_env_overrides`，不新增运行时读取路径；`effective()` 仍是唯一出口 |

---

## 9. 一句话总结

**把「读文档猜配置」变成「提取候选 → 人确认 → 固化事实」，把「密钥」变成「引用 + 执行时解析」，把「逐命令审批」变成「dry-run 阶段产物 + 分环境策略」；Agent 在规划和生成上保持自由，只在触碰真实系统的阶段边界上受控。**
