# 任务清单：流水线环境注入与中间件健康修复

## P0 共享解析器

- [x] T1 新增 `platform/agent-domain/src/main/java/top/rongx/agent/domain/env/ProjectEnvFiles.java`
  - 有界扫描 `.env` / `.env.example`（深度 5，跳过构建/依赖目录）
  - 排序固定：`.env` 优先于 `.env.example`，空值不占位、不覆盖非空值
  - 解析 `entrypoint.sh` 的 `require VAR`（优先），其次 compose `${VAR:?`，最后回退空值+非可选
  - 返回 `Declared(values, keys, required)`
- [x] T2 新增单测 `platform/agent-domain/src/test/java/top/rongx/agent/domain/env/ProjectEnvFilesTest.java`
  - 用临时目录 fixture 覆盖：优先级、空值、可选注释、entrypoint 权威性

## P1 网关（自检 / 有效环境）

- [x] T3 `EnvBindingService.scanRequiredKeys` 改为委托 `ProjectEnvFiles.scan(repo).required()`
- [x] T4 `EnvBindingService.draft`：`keys` 用 `declared.keys()`，`missing` 只统计 required 且（有效环境+源码）均无值
- [x] T5 `EnvironmentController.envCheck`
  - 必填值判定加入源码 fallback，返回 `sourceProvidedVars`
  - 返回 `referencedCount` / `referencedRunningCount` / `middlewareHealthy`
  - `problems` 不含"未启动"
- [x] T6 `EnvironmentController.effective` + `resourceView` 增加资源 `referenced` 与顶层计数
- [x] T7 `EnvTools.env_summary` 健康口径改为"被项目引用的资源"

## P2 执行层（注入）

- [x] T8 `EnvProvisioner` 增加默认方法 `seedDeclared(projectId, env)`
- [x] T9 `DockerEnvProvisioner` 用 `ProjectEnvFiles` 替换 `readDeclaredEnv` / `collectEnvFiles`，实现 `seedDeclared`
- [x] T10 `BuiltInPipelineEngine.executeLocalDev` 在计算 `deployEnv` 前调用 `seedDeclared` 并写日志

## P3 前端

- [x] T11 `platform/web/src/api/settings.ts` 补字段类型
- [x] T12 `EnvSummary.vue` 按 `referencedCount/referencedRunningCount` 展示健康
- [x] T13 `ProjectEnvPanel.vue` 同上，并展示 `sourceProvidedVars`

## P4 验收

- [x] T14 平台后端编译 + 单测：`ProjectEnvFilesTest`、`DockerEnvProvisionerSeedTest`、`DeployEnvPrecheckTest`、`RunHistoryStoreTest` 全绿
- [x] T15 前端 `npm run build` 通过（`vue-tsc -b && vite build`）
- [x] T16 重启平台后端（Java 变更需重启一次；不重复启动）
- [x] T17 手工验收：
  - `RUOYIWNZ` dev/test 自检无 `SMTP_USERNAME` / `RONGX_AES_*_PREVIOUS` 误报（API：`ok=true`、`requiredCount=3`；浏览器：显示"自检通过（3 个必填变量齐全）"）
  - 真实仓库探针确认必填恰为 `DB_HOST/REDIS_HOST/TOKEN_SECRET`，`DB_HOST` 取到 `.env` 的值
  - 环境页显示"中间件 3/3（dev）、1/1（test）运行"，仅统计被引用资源
  - dev 运行日志的注入分支由 `DockerEnvProvisionerSeedTest` 覆盖（项目仓库当前 `mvn package` 失败，dev 实跑停在 build 阶段，无法到达 deploy 日志）
- [x] T18 检查 `specs/dev-local-deploy/tasks.md`：仅描述 action 阶段脚本渲染，与本变更无冲突，无需修改

## 备注

- 全量 `mvn test`（gateway 集成测试）仍有既有失败：`ActionUsabilityTest`（项目 RY 未注册）、`DeployStageTest`（分支 guard/E-504）、`DualStackPipelineTest`、`IntakeE2ETest`，均为环境/夹具问题，不涉及本次改动的代码路径。
- 顺手修复 `RunHistoryStoreTest` 的构造参数（`RunSnapshot` 早已扩展为 13 个字段），否则模块测试无法编译。
