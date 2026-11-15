---
type: plan
summary: "环境运行时一致性任务清单"
status: in-progress
code_refs:
  - platform/agent-executor/src/main/java/top/rongx/agent/executor
---
# 环境一致性修复任务

> 2026-11-15 按代码复核：T1–T4 与实现一致；T5 未执行；T6 只完成了“端点幂等收敛”，原“已有非空覆盖不强制覆盖”未实现；实跑验收（T7 等）是历史记录，本轮未重跑。

## P0（已完成并实跑验证）

- [x] T1 统一引用判定口径
  - 文件：`platform/agent-domain/src/main/java/top/rongx/agent/domain/env/StackAdapter.java`
  - `isReferenced` 支持 `internalHost / name` 精确匹配；`host` 匹配必须端口一致（多实例共用 127.0.0.1）
  - minio 只比 endpoint 的 host 段 + 端口，避免 `minio` 命中 `test-minio`
  - 单测：`StackAdapterTest` 6 个用例（本轮确认存在，未重跑）
  - 验收：`effective dev` 中 `agent-dev-ruoyiwnz-mysql/redis` 均 `referenced=true`（历史实跑）

- [x] T2 dev 端点派生用宿主机发布端口
  - 文件：`platform/agent-executor/src/main/java/top/rongx/agent/executor/DockerEnvProvisioner.java`
  - `derive(r, internalAddressing)`；`internalAddressing = !"dev".equalsIgnoreCase(env)`
  - 新增 `syncEndpoints`：dev 下把历史容器名覆盖纠正为 `127.0.0.1 + 发布端口`
  - `applyPgOverrides` 同步 dev 分支
  - 单测：`DockerEnvProvisionerAddressingTest` 2 例（本轮确认存在，未重跑）
  - 验收（run-6f208d46）：`DB_HOST=127.0.0.1/DB_PORT=13306`、`REDIS_HOST=127.0.0.1/REDIS_PORT=16379`（历史实跑）

- [x] T3 dev 本机进程可见
  - 文件：`LocalDevHostDeployer.java`、`PipelineConfig.java`
  - deploy 成功登记 ServiceLease（backend/frontend，含 pid/port），stop 时释放
  - 日志写入 `<workspaceRoot>/_platform/local/<projectId>/{backend,frontend}.log`，与 `LocalProcessServiceRuntime.logFile` 同路径
  - 单测：`LocalDevHostDeployerLeaseTest` 1 例（本轮确认存在，未重跑）
  - 验收：`projects/RUOYIWNZ/services?env=dev` backend/frontend 均 RUNNING；`backend.log` 实时写入（历史实跑）

## P1

- [x] T4 明文凭证：判定为项目既定规则，不修改
  - 依据：用户仓库 `docs/standards/RULES.md` §10 明确「运行/部署配置允许直接写入凭据，不依赖环境变量注入，gitleaks 已豁免」
  - 结论：审计报告中「明文凭证仍在入库文件」**不构成缺陷**；如需改变，须先推翻 RULES.md §10
  - 未改动 `application.yml` / `application-druid.yml`

- [ ] T5 提交边界（未执行提交，按规则只给命令）
  - 平台仓库：`git add agent-domain/src agent-executor/src agent-tools/src agent-infra/src` + 测试
  - 用户仓库：只提交 5 个关键配置文件，排除 `dist`
  - 说明：提交动作需用户明确授权后执行

## P2

- [x] T6a dev 端点幂等收敛（已完成）
  - `syncEndpoints` 在 dev 下把被引用资源的 endpoint 统一为 `127.0.0.1 + 发布端口`，连续两次 release 结果不变
  - 验收：连续两次 dev release 后 `effective/reveal` 值不变（历史实跑）
- [ ] T6b 原方案“dev 已有非空覆盖时不强制覆盖 endpoint”**未实现**
  - `applyOverrides(forceEndpoint=true)` 仍会重写 endpoint；自动诊断对 dev 的写入也无只读闸门

## 实跑验收

- [x] T7 平台重启后顺序验证（历史记录，2026-11-15 未重跑）
  1. `projects/RUOYIWNZ/services?env=dev` → backend/frontend RUNNING（pid 正确）
  2. `backend.log` 有实时日志（app_logs 数据源已通）
  3. `effective dev` → mysql/redis `referenced=true`，redis 状态 RUNNING
  4. `effective/reveal dev` → `DB_HOST=127.0.0.1`、`REDIS_HOST=127.0.0.1`
  5. dev release（run-6f208d46）→ SUCCEEDED，`[health] 200`
