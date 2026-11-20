---
name: sandbox-images
description: 构建镜像就绪自动收敛：拉取失败排查、退避窗口、缓存源配置、镜像状态面板
covers: [DockerSandbox, ImageProvisioner, image_readiness, DbImageReadinessStore, SandboxImageController, SandboxImagePanel]
when_to_read: 构建镜像缺失/拉取超时/docker pull 失败/镜像缓存/沙箱配置/工具链镜像面板
budget: 120
---

# 构建镜像就绪（sandbox-images）

## 关键文件

| 文件 | 角色 |
|---|---|
| `platform/agent-executor/.../ImageProvisioner.java` | 唯一就绪策略：预检与自动修复共用 |
| `platform/agent-executor/.../DockerSandbox.java` | `ensureImageReady()` 委托给 provisioner |
| `platform/agent-executor/.../BuiltInPipelineEngine.java` | `provisionImages()`：阶段循环前并行点亮 |
| `platform/agent-infra/.../DbImageReadinessStore.java` | 状态存取（表 `image_readiness`） |
| `platform/agent-infra/.../db/migration/common/V6__image_readiness.sql` | 建表 |
| `platform/agent-gateway/.../SandboxImageController.java` | 只读接口 `/api/v1/settings/sandbox/images` |
| `platform/web/.../SandboxImagePanel.vue` + `imageReadiness.ts` | 设置页面板 + 纯函数展示逻辑 |

## 关键事实

- 状态按 **(host, image)** 记录：镜像层缓存属于某台机器的 Docker 守护，不能按 image 全局共享。
- **只信复核**：就绪与否由平台自己跑的 `docker image inspect` 判定，`docker pull` 退出码 0 不算数。
- 失败按镜像指数退避：`min(pull-backoff-max, pull-backoff × 2^(attempts-1))`；窗口内本机快速失败。
- 超时两档：停滞（`pull-idle-timeout-seconds` 无输出）≠ 总量超时（`pull-timeout-seconds`）。
- 开跑前 `provisionImages` 统一供给所有阶段镜像，任一失败整体失败并落成 run 的首个阶段结果。
- `READY` 不是"跳过"：仍花一次 inspect 复核（机器可能被清过镜像）。
- 前端 `retryInSeconds=0` 只代表可重试，不代表已就绪（`imageReadiness.test.ts` 锁住这条）。

## 配置（application.yml `agent.sandbox.*`，均有环境变量兜底）

`pull-timeout-seconds`(900) / `pull-idle-timeout-seconds`(60) / `pull-attempts`(2) /
`pull-backoff-seconds`(60) / `pull-backoff-max-seconds`(900) / `pull-mirrors`（加速源，仅 Docker Hub）/
`host`（多机共库时区分主机维度）。**不要写死阈值**——用户明确要求过。

## 怎么验证

- 单测：`ImageProvisionerTest`、`ImagePullRepairTest`、`DbImageReadinessStoreTest`、`imageReadiness.test.ts`
- 接口：`SandboxImageControllerTest`；OpenAPI 快照改动后 `-Dapi.docs.update=true` 重新生成
- 界面：`node platform/web/scripts/sandbox-images-check.mjs [base] [截图]`（需后端+dev server）

## 找历史

`python tools/docs/find.py --impact ImageProvisioner`；模块视图 `docs/views/modules/agent-executor.md`。
