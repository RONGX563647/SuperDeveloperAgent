# 环境一致性修复（dev 视角）规格

> 状态：**P0 全部落地，P1 按既定规则不修改，P2-1 未按原意实现（2026-11-15 核对）**。
> §2 是当时的审计证据快照（含 run-id），不是今天的待办；§4 中未标明“未实现”的 P0 条目均已落地。
> 剩余差异：`applyOverrides(forceEndpoint=true)` 仍会覆盖 dev 已有非空 endpoint，`seedDeclared` 只能补非 endpoint 的缺失键。

## 1. 背景

上一轮 `../env-inject-health` 修复了「注入前自检误报」「健康检查只算引用中间件」。
审计复验提出：健康检查确实修对了，但 Redis 指向、平台可见性、中间件登记、明文凭证、未提交改动仍存在问题，
并怀疑「把一个问题换成了另一个问题」。

本规格先逐条判定这些问题是否存在，再给出可执行的修复方案。

## 2. 逐条判断（证据优先）

| # | 审计结论 | 判定 | 证据 |
|---|---|---|---|
| 1 | 健康检查修对并生效 | **成立** | `run-3a65beea`：`[health] 200 http://127.0.0.1:8688/actuator/health/liveness`，`SUCCEEDED` |
| 2 | Jenkinsfile 明文密码清掉 | **成立** | 当前 `Jenkinsfile` 已无 `mysql_nsCAwN` 字面量 |
| 3 | Redis 兜底默认值去掉 | **成立** | `docker/backend/application.yml:27` 为 `${REDIS_HOST:redis}` |
| 4 | **A. Redis 从"能连通"变成"根本连不上"** | **部分成立：配置层成立，运行层不成立** | `effective/reveal`: `REDIS_HOST=agent-dev-ruoyiwnz-redis`、`REDIS_PORT=6379`；但 dev 本机分支 `localHostEnv → resolveToHost` 会改写成 `127.0.0.1:16379`（`BuiltInPipelineEngine.java:1662-1701`）；`port_check 127.0.0.1:16379` = open |
| 5 | **B. dev 后端 exit 1，起不来** | **曾经发生，当前已恢复** | `run-6a0a33a1` 失败；随后 `run-3a65beea`（02:46:41）后端就绪 + health 200。当时 `app_logs` 为空是 C 导致的取证缺口，不是"现在起不来" |
| 6 | **C. 平台看不到 dev 应用** | **成立，是真 bug** | `RuntimeTools.appendHostProcesses` 只读 `envStore.serviceLeases`（`RuntimeTools.java:339`）；而 `LocalDevHostDeployer` 只写 `backend.pid/backend.log`，**从不登记 ServiceLease** |
| 7 | **D. `env_summary dev` own 0/0** | **成立，是真 bug** | `StackAdapter.isReferenced` 只比较 `resource.host()` 与 `DB_HOST/REDIS_HOST`（`StackAdapter.java:79-92`）；当值为容器名时恒为 false |
| 8 | **E. Redis 容器活着但登记 STOPPED** | **成立，与 D 同根** | 未引用 → `EnvRuntime.ensureRunning` 不刷新该资源状态；容器实际 Up |
| 9 | **F. 明文凭证仍在入库文件** | **不成立（项目既定规则）** | 用户仓库 `docs/standards/RULES.md` §10 明确允许运行/部署配置直写凭据并豁免 gitleaks；`application.yml:148`、`application-druid.yml:15` 是该决定的落地，不按缺陷处理 |
| 10 | **G. 改动全在工作区未提交** | **成立** | `git status` 大量未提交；dev 用工作区、test/prod 用 sha，所以修复只对 dev 生效 |
| 11 | 失败后被自动 release agent 调 `env_ensure` 覆盖环境 | **成立（机制存在）** | 平台自动诊断会改写环境覆盖值，掩盖构建侧真实失败 |

**额外发现（审计未列，历史）**：当时 `seedDeclared` 静默无效：它只补“缺失”的键，而 `applyOverrides(forceEndpoint=true)` 已先把 `DB_HOST/REDIS_HOST` 写成端点。
现行已由 T2 的 `syncEndpoints` 修复到“dev 端点统一为 127.0.0.1 + 发布端口”，日志也会出现 `[env] 已按项目 .env 注入变量`；
但**源码 `.env` 的 DB_HOST/REDIS_HOST 仍赢不了平台端点覆盖**，这是设计取舍，不是再回退到旧行为。

## 3. 根因

- **R1** 引用判定口径与端点改写口径不一致：`isReferenced` 只认 `host`，`resolveToHost` 认 `internalHost/name`。
  结果：运行时能连，平台判定"未引用" → own 0/0、STOPPED 假死、不刷新。
- **R2** dev 端点派生写内网名：`DockerEnvProvisioner.derive` 对 `LOCAL_DOCKER` 一律用 `internalHost + containerPort`，
  未区分 dev 本机模式（宿主机必须用 `127.0.0.1 + publishedPort`）。
- **R3** dev 托管进程未登记：`LocalDevHostDeployer` 与 `LocalProcessServiceRuntime` 是两套状态/日志目录，互不可见。
- **R4** 覆盖顺序：`applyOverrides` 强制写 endpoint，`seedDeclared` 只补缺失，源码 `.env` 被压住。
- **R5** 凭证与版本库卫生。

## 4. 修复方案

### P0-1 统一引用判定口径（修 D/E）

文件：`../../../platform/agent-domain/src/main/java/top/rongx/agent/domain/env/StackAdapter.java`

`isReferenced` 改为：取变量值 `v = values.get(hostKey(kind))`（minio 取 `MINIO_ENDPOINT`），
资源命中条件为 `matches(v, resource.host()) || matches(v, resource.internalHost()) || matches(v, resource.name())`，
其中 `matches` 做 null/blank 安全的大小写不敏感比较；minio 仍用 endpoint 包含任一地址。

影响面（无需改动，自动修正）：`EnvironmentController.effective`、`EnvTools.env_summary`、
`EnvRuntime.ensureRunning` 引用集合。

验收：
- `GET /api/v1/settings/env/effective?projectId=RUOYIWNZ&env=dev`：mysql/redis 的 `referenced=true`，
  `referencedCount` 覆盖本项目实际使用的 2-3 个资源。
- `env_summary dev`：`own` 非空、`healthy` 与 `docker ps` 一致。
- 停止 redis 容器后 `ensureRunning` 能重新拉起（登记不再 STOPPED 假死）。

### P0-2 dev 端点派生用宿主机发布端口（修 A 的配置层 + R4）

文件：`../../../platform/agent-executor/src/main/java/top/rongx/agent/executor/DockerEnvProvisioner.java`

- `derive(EnvResource r)` 改为 `derive(EnvResource r, boolean internal)`。
- `applyOverrides` 传 `internal = !"dev".equalsIgnoreCase(r.env())`（沿用"dev = 本机模式"的既有约定，
  与 `BuiltInPipelineEngine.localDevBranch` 一致）。
- dev 时 `host = r.host()`（`127.0.0.1`）、`port = r.port()`（发布端口 16379/13306），
  test/prod 保持 `internalHost + containerPort`。
- 同步更新 `applyPgOverrides` 的同类分支。

效果：`effective/reveal` 里 dev 的 `REDIS_HOST=127.0.0.1`、`REDIS_PORT=16379`，
设置页展示与运行时注入一致；`seedDeclared` 的"只补缺失"不再与 endpoint 冲突。

验收：
- `effective/reveal?env=dev`：`REDIS_HOST=127.0.0.1`、`REDIS_PORT=16379`、`DB_HOST=127.0.0.1`、`DB_PORT=13306`。
- `effective?env=test`：仍为容器名 + 容器端口（不回归）。
- 跑一次 dev release，后端就绪且 `[health] 200`。

### P0-3 dev 本机进程登记 + 日志统一（修 C）

文件：
- `../../../platform/agent-executor/src/main/java/top/rongx/agent/executor/LocalDevHostDeployer.java`
- `../../../platform/agent-executor/src/main/java/top/rongx/agent/executor/PipelineConfig.java`
- `../../../platform/agent-infra/src/main/java/top/rongx/agent/infra/env/LocalProcessServiceRuntime.java`

做法：
1. `LocalDevHostDeployer` 构造注入 `EnvironmentStore`；`deploy` 成功后
   `saveServiceLease(new ServiceLease(projectId, env, "backend", backendPort, "RUNNING", pid, now))`，
   前端同理；`stopIn` 中 `deleteServiceLease(projectId, env, service)`。
2. 统一状态目录：`LocalProcessServiceRuntime.logFile` 改为与 `LocalDevHostDeployer.stateDir` 同一根
   （以 `workspaceRoot` 为基准），文件名为 `backend.log` / `frontend.log`。
   这样 `app_logs dev` 的 `services.logTail` 能读到真实日志。
3. `PipelineConfig.localDevHostDeployer()` 增加 `EnvironmentStore` 参数。

验收：
- `env_runtime_status dev`：`runtime: host-process`，列出 backend/frontend 的 status/port/pid/health。
- `app_logs dev`：返回 `backend.log` 尾部（不再"没有日志可读"）。
- `stopLocalDev` 后：lease 消失，`env_runtime_status dev` 不再显示 RUNNING。

### P1-1 明文凭证外部化（修 F，需用户确认后改用户仓库）

文件（用户仓库 `E:\CODE\developing\ruoyi-wnz`）：
- `RuoYi-Vue/ruoyi-admin/src/main/resources/application.yml:148`
  `secret` 改为 `${JWT_SECRET:}`，并在启动时校验非空/长度；移除 `abcdefghijklmnopqrstuvwxyz`。
- `RuoYi-Vue/ruoyi-admin/docker/backend/application-druid.yml:15`
  `password` 改为 `${DB_PASSWORD:}`。

验收：`grep -R "mysql_nsCAwN\|abcdefghijklmnopqrstuvwxyz"` 在入库文件中零命中；
dev 启动时由已有环境变量提供值，health 200。

### P1-2 提交与生效边界（修 G）

- 平台仓库（`agentscope-ruoyi`）：P0-1/P0-2/P0-3 的代码与测试单独提交。
- 用户仓库（`ruoyi-wnz`）：仅提交 5 个关键配置文件，排除 `dist` 等构建产物（当前 399 项里绝大多数是 dist 删除）。
- **不擅自提交用户仓库**：由用户确认后执行，或给出精确 `git add <5 个文件>` 命令交给用户。

验收：`git status --porcelain` 只剩构建产物/临时文件；test 用 sha 构建时包含本次配置修复。

### P2-1 dev 不被自动补救覆盖（未按原意实现）

> 现行实现是“端点幂等收敛”：dev 下无论谁触发 `env_ensure`，`syncEndpoints` 都会把 endpoint 重写为 `127.0.0.1 + 发布端口`，值稳定不变，但**不是**“已有非空覆盖就不强制覆盖”。自动诊断 agent 对 dev 的写建议也未加只读闸门。原方案（只在缺失时补）**未实现**。

## 5. 非目标

- 不重构 release agent 的自动诊断流程本身。
- 不改 test/prod 的容器内网寻址方式。
- 不处理 `dist` 等构建产物的版本库策略（只把它们排除出提交）。

## 6. 验收总清单

1. 单测（已存在）：`StackAdapterTest`（6 例）、`DockerEnvProvisionerAddressingTest`（2 例）、`LocalDevHostDeployerLeaseTest`（1 例）。
2. `mvn -q -pl agent-domain,agent-executor,agent-infra,agent-tools -am test` 通过（历史记录，2026-11-15 未重跑）。
3. 重启平台后 `env_runtime_status dev`、`app_logs dev`、`env_summary dev`、`effective/reveal` 四项符合上述断言（历史实跑 run-6f208d46）。
4. 跑一次干净 dev release：后端就绪、`[health] 200`、无 Redis 相关报错（历史实跑）。

## 7. 需要用户决策（已决）

1. 明文凭证（P1-1）：**不修改**。按用户仓库 `docs/standards/RULES.md` §10，运行/部署配置允许直写凭据，视为既定规则。
2. 平台仓库提交（P1-2）：**未执行**；只给命令，需用户明确授权后再提交。
