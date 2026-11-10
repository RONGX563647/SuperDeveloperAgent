# 环境一致性修复任务

## P0（已完成并实跑验证）

- [x] T1 统一引用判定口径
  - 文件：`platform/agent-domain/src/main/java/top/rongx/agent/domain/env/StackAdapter.java`
  - `isReferenced` 支持 `internalHost / name` 精确匹配；`host` 匹配必须端口一致（多实例共用 127.0.0.1）
  - minio 只比 endpoint 的 host 段 + 端口，避免 `minio` 命中 `test-minio`
  - 单测：`StackAdapterTest` 6 个用例全绿
  - 验收：`effective dev` 中 `agent-dev-ruoyiwnz-mysql/redis` 均 `referenced=true`

- [x] T2 dev 端点派生用宿主机发布端口
  - 文件：`platform/agent-executor/src/main/java/top/rongx/agent/executor/DockerEnvProvisioner.java`
  - `derive(r, internalAddressing)`；`internalAddressing = !"dev".equalsIgnoreCase(env)`
  - 新增 `syncEndpoints`：dev 下把历史容器名覆盖纠正为 `127.0.0.1 + 发布端口`
  - `applyPgOverrides` 同步 dev 分支
  - 单测：`DockerEnvProvisionerAddressingTest` 全绿
  - 验收（run-6f208d46）：`DB_HOST=127.0.0.1/DB_PORT=13306`、`REDIS_HOST=127.0.0.1/REDIS_PORT=16379`

- [x] T3 dev 本机进程可见
  - 文件：`LocalDevHostDeployer.java`、`PipelineConfig.java`
  - deploy 成功登记 ServiceLease（backend/frontend，含 pid/port），stop 时释放
  - 日志写入 `<workspaceRoot>/_platform/local/<projectId>/{backend,frontend}.log`，与 `LocalProcessServiceRuntime.logFile` 同路径
  - 单测：`LocalDevHostDeployerLeaseTest` 全绿
  - 验收：`projects/RUOYIWNZ/services?env=dev` 返回 backend pid=34436、frontend pid=33348 均 RUNNING；`backend.log` 实时写入

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

- [x] T6 dev 不被自动补救覆盖
  - 由 T2 `syncEndpoints` 保证幂等：无论谁再触发 `env_ensure`，dev 的 endpoint 始终收敛到同一组 `127.0.0.1 + 发布端口`
  - 验收：连续两次 dev release 后 `effective/reveal` 值不变

## 实跑验收

- [x] T7 平台重启后顺序验证
  1. `projects/RUOYIWNZ/services?env=dev` → backend/frontend RUNNING（pid 正确）
  2. `backend.log` 有实时日志（app_logs 数据源已通）
  3. `effective dev` → mysql/redis `referenced=true`，redis 状态 RUNNING
  4. `effective/reveal dev` → `DB_HOST=127.0.0.1`、`REDIS_HOST=127.0.0.1`
  5. dev release（run-6f208d46）→ SUCCEEDED，`[health] 200`，日志含 `[env] 中间件地址已按本机模式校正`
