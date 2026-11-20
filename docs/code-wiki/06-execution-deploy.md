---
type: code-wiki-execution
summary: 执行面实现：动作解析与运行、Docker 沙箱、流水线引擎的编排/超时/恢复、部署器与环境供给、自动修复
---

# 执行与部署

执行面在 `agent-executor`，不依赖任何模型 SDK（ArchUnit A3）：模型只能通过工具触发已定义的能力。

## 1. 动作解析（ActionResolver）

配方优先级（`RecipeActionResolver`）：

```text
ci/actions.yml（仓库显式声明）
  → Taskfile.yml
  → Makefile 目标探测
  → 内置默认（按项目结构）
```

解析结果 `ActionSpec` 包含：`argv`、工作目录、超时、环境变量、是否需要 Docker、产物路径。

安全约束：

- **不接受 shell 字符串**：`argv` 列表直执行，`SHELL_REJECTED (E-202)` 拒绝可疑形态。
- `MVN_DEPLOY_FORBIDDEN (E-211)` 禁止构建期发布制品。
- 参数校验失败 `ARGV_INVALID (E-201)`，可执行文件缺失 `EXECUTABLE_NOT_FOUND (E-209)`。

## 2. 动作执行（ActionRunner）

| 实现 | 场景 |
|---|---|
| `DockerActionRunner` | 默认：命令包进受限容器 |
| `ProcessActionRunner` | 宿主直跑（受限场景，如本机 dev 托管） |

`AbstractActionRunner` 统一处理：超时、取消、心跳、日志采集与脱敏、退出码映射。

## 3. Docker 沙箱

[DockerSandbox](file:///workspace/platform/agent-executor/src/main/java/top/rongx/agent/executor/DockerSandbox.java) 把 argv 包装成一条 `docker run`：

```text
--rm
--entrypoint=                    # 清空镜像 ENTRYPOINT，argv 直执行，不套 shell
--network <network>              # 默认受限网络
--memory 2g  --cpus <n>  --pids-limit 512
--user 1000:1000                 # 非 root
--security-opt no-new-privileges
--cap-drop ALL
--read-only                      # 根只读
--tmpfs ...                      # 可写临时目录
--workdir /ws                    # 工作目录挂载
```

其它能力：

- `ensureImageReady()`：镜像就绪入口，内部委托给统一的 `ImageProvisioner`（见 §3.1）。
- `prepareCacheVolume()`：依赖缓存卷，归属与运行用户一致。
- `beginContainer()` / `--name`：长驻容器（如前后端 dev server）。
- 可选 `--runtime`（如 gVisor）、npm registry、init 镜像。

> 沙箱**不注入凭据**。需要私服时手工挂载只读 `settings.xml`，避免构建期泄露。

### 3.1 镜像就绪自动收敛

镜像缺失不再靠“某次 `docker run` 报错”才暴露，而是开跑前统一点亮：

| 环节 | 实现 |
|---|---|
| 唯一策略 | [ImageProvisioner](file:///workspace/platform/agent-executor/src/main/java/top/rongx/agent/executor/ImageProvisioner.java)：预检与自动修复共用同一份实现，避免两套判定标准 |
| 开跑前供给 | `BuiltInPipelineEngine.provisionImages`：阶段循环之前取出全部 `stage.image()` 去重并行供给，任一失败即整体失败并把原因落成该 run 的首个阶段结果 |
| 状态记录 | 按 **(host, image)** 存 `image_readiness`（`ImageReadinessStore` / `DbImageReadinessStore`），跨 run 复用；`READY` 时只花一次 `image inspect` 复核，不重复拉取 |
| 失败退避 | 指数退避（`pull-backoff-seconds` → 上限 `pull-backoff-max-seconds`），窗口内本机直接失败返回，不逐次重拉重等 |
| 判定依据 | 只看平台侧 `image.inspect`，**不看 `docker pull` 退出码**（退出码 0 不代表镜像真的在本地可用） |
| 超时两档 | 停滞（`pull-idle-timeout-seconds` 内无输出）与总量上限（`pull-timeout-seconds`）——前者通常是源慢、后者多为网络不可达 |
| 观测 | `GET /api/v1/settings/sandbox/images` + 设置页「工具链镜像」面板 |

所有阈值都可配置（`agent.sandbox.pull-*` / `pull-mirrors` / `host`），不写死在代码里；加速源只是“可选加成”，未配置时仍走守护进程自带 mirrors。


## 4. 流水线引擎

[BuiltInPipelineEngine](file:///workspace/platform/agent-executor/src/main/java/top/rongx/agent/executor/BuiltInPipelineEngine.java) 实现 `PipelineEngine`，是执行面的核心。

### 4.1 定义来源

- 仓库 `ci/pipeline.yml`（首选）。
- 平台侧定义（`PipelineDefStore`，项目接入时生成/维护）。
- 内置流水线：`release`、`verify`。

定义不可用 → `E-220`；格式非法 → `E-221`；未合并 → `E-508`。

### 4.2 关键流程

```text
trigger(projectId, pipelineId, params)
  ├─ 解析定义 → StageDefinition[]（stage 可含多 action）
  ├─ checkout sha（GitWorktreeManager）
  ├─ stage 循环：
  │    action → ActionResolver.resolve() → ActionRunner.run()
  │    记录开始/结束、日志尾部、JUnit/覆盖率摘要、镜像标签
  ├─ deploy stage → Deployers.deploy(request)（按 target 选具体部署器）
  ├─ health check：HTTP/端口探测（窗口 ≥ 30s）
  └─ 结果 → RunHistoryStore + RunLedger + 事件
```

支持：`dryRun()`（不执行、返回计划与风险）、`preview()`（阶段预览）、`status()`、`progress()`、`stages()`、`logs` 分页、`cancel()`、`retry()`、`hasRun()`。

### 4.3 超时与恢复（看门狗）

| 参数 | 默认 |
|---|---|
| 单 stage 预算 | 5 分钟（内置 stage；配方动作默认超时 15 分钟） |
| 看门狗 tick | 5 秒 |
| 心跳落盘 | 10 秒 |
| 心跳日志 | 120 秒 |
| 看门狗宽限 | 5 分钟 |
| 强制终止等待 | 60 秒 |
| 健康探测超时 | 2 秒（窗口最小 30 秒） |
| 保留镜像数 | 5 |

进程重启后依据 `RunHistoryStore` 的心跳恢复/判死；`AutoCloseable.close()` 负责收尾容器与线程池。

## 5. 部署器

[Deployers](file:///workspace/platform/agent-executor/src/main/java/top/rongx/agent/executor/Deployers.java) 是组合注册表：

```java
Deployers(List<Deployer> delegates)
boolean supports(DeployTarget)
DeployResult deploy(DeployRequest)
```

| 目标 | 实现 | 行为 |
|---|---|---|
| `docker` | `DockerDeployer` | 拉镜像、替换容器、健康检查、保留旧镜像 |
| `host` | `HostDeployer` | 发布产物到宿主目录、重启进程 |
| `server` | `ServerDeployer` | SSH 远程执行发布脚本（需配置） |
| dev 本机 | `LocalDevHostDeployer`（引擎内，非 `DeployTarget` 枚举） | dev 固定本机托管：jar + 前端 dist，一键停止交还 IDE |

未注册目标 **fail-closed**：`DEPLOY_TARGET_UNCONFIGURED (E-505)`；目标无法判定 `E-504`；前置条件缺失 `E-501`；回滚版本缺失 `E-507`。

## 6. 环境供给（中间件）

- `DockerEnvProvisioner`：按配方为项目创建 Redis/MySQL 等容器（端口、卷、网络、健康检查）。
- `DockerEnvRuntime`：启停、状态、连接串发现。
- 失败映射：`MIDDLEWARE_START_FAILED (E-701)`、`ENV_PREPARE_FAILED (E-702)`、`LOCAL_IMAGE_UNAVAILABLE (E-705)`、`SECRET_UNRESOLVED (E-704)`。
- dry-run 过期：`DRY_RUN_STALE (E-706)`。

## 7. 自动修复闭环

`AutoRepairCoordinator` 在 stage 失败后按顺序尝试：

```text
1. 确定性修复：ImagePullRepair（重新拉基础镜像）、DockerDaemonRepair（重启/校验 daemon）
2. 平台复核：修复后重新执行失败动作
3. 诊断 Agent：AgentDiagnosisRepair（只读分析日志，输出原因与建议，必要时产出受限补丁）
4. 仍失败 → 结束并留下证据
```

每次修复都记录在 `PipelineEngine.AutoRepairView`，前端可展示修复轨迹。

## 8. 运行账本与历史

| 组件 | 作用 |
|---|---|
| `RunHistoryStore` | 每 run 的 stage、日志尾部、产物、心跳、修复记录 |
| `ImageVersionStore` | 镜像版本与回滚候选 |
| `DbRunLedgerRepository` | 统一账本（CHAT/DEPLOY/WORKSPACE/BUGFIX/PIPELINE） |
| `TriggerScheduler` | 同项目同环境串行触发，避免并发踩踏 |
| `PomVersionSync` / `ReleaseVersion` | 版本号同步与发布标签 |

## 9. 与治理的衔接

执行器本身**不做策略判断**，策略在工具/治理层完成。执行器只保证：

- 命令形态受控（argv、沙箱）。
- 超时、资源、日志脱敏。
- 失败可归因（错误码 + 证据）。
- 部署目标明确，不静默降级。
