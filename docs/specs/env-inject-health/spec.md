# 流水线环境注入与中间件健康修复

## 背景与问题

用户报告两个流水线/环境配置问题（项目 `RUOYIWNZ`，仓库 `E:\CODE\developing\ruoyi-wnz`）：

1. **注入前自检误报缺变量**
   设置页提示：`注入前自检未通过：缺少必填环境变量：SMTP_USERNAME、RONGX_AES_KEY_PREVIOUS、RONGX_AES_IV_PREVIOUS`。
   这三个变量在仓库 `RuoYi-Vue/ruoyi-admin/docker/.env.example` 中是**空值且注释标注「可选」**，
   源码本身不需要它们也能启动；但平台把 `.env.example` 里出现的**所有 key** 都当成必填。
   同时用户反馈「很多环境都在源码上有了 但是还是没有注入」——`.env` 里的真实值在部分链路（尤其是 dev 本机部署）没有进入注入集合。

2. **开发环境健康判定过严**
   「不是所有 docker 都启动才对，是项目需要的中间件有启动就是健康的」。
   当前触发区/项目环境面板显示 `中间件 x/总数 运行`，总数包含环境共享、其它项目、以及本项目`.env`根本没引用的资源，
   看起来像"不健康"；后端 `env-check` 已不把"未启动"当问题，但前端没有按"被项目引用"过滤。

> 状态：**现行（2026-11-15 按代码复核）**。R1–R4 已落地，R5 的实跑验收为历史记录。§2 的“现状定位”是**修复前**的问题快照，不是现在的代码；现状以“方案”小节中标注“已实现”的条目为准。

## 现状定位（修复前，历史）

| 位置 | 当时现状 | 问题 |
| --- | --- | --- |
| `platform/agent-gateway/.../settings/EnvBindingService.java` | 读取 `.env.example` 全部 key 当必填 | 可选/有默认值的变量被误判必填 |
| `platform/agent-gateway/.../web/EnvironmentController.java` `envCheck` | 只对照 `envStore.effective` 的值 | 没考虑仓库源码里的值；前端把未启动展示为“不健康” |
| `EnvironmentController.effective` / `resourceView` | 返回全部环境资源 | 未标记“是否被本项目引用” |
| `platform/agent-executor/.../DockerEnvProvisioner.java` | 固定候选 + 深度遍历，`putIfAbsent` 合并 | `.env.example` 先占位，`.env` 非空值不生效 |
| `BuiltInPipelineEngine.executeLocalDev` | dev 只注入 `envStore.effective` | 不注入仓库 `.env` |
| `EnvSummary.vue` / `ProjectEnvPanel.vue` | `running/total` 用全部 resources | 共享/未引用资源拉低健康 |
| 仓库 `docker/entrypoint.sh` | 有 `require` 声明 | 平台未利用 |

## 目标

- R1 自检只把**项目真正必需**的变量判为必填；`.env.example` 中有默认值、或注释标注可选的变量不再拦截。
- R2 仓库 `.env` 的非空值必须被稳定采集并注入（`.env` 优先于 `.env.example`，空值不得覆盖非空值）；dev 本机部署同样注入。
- R3 中间件健康只看"被项目引用的资源"；未启动不算异常（部署时会启动），未被引用不计入健康分母。
- R4 不改变现有 E-509 / E-701 / E-702 的失败语义，只收紧判定口径。
- R5 有单测覆盖；实跑验收通过。

## 方案

### 1. 共享解析器（已实现，domain 层）

文件 `platform/agent-domain/src/main/java/top/rongx/agent/domain/env/ProjectEnvFiles.java`（纯静态工具，无 Spring 依赖）：

```java
public final class ProjectEnvFiles {
    public record Declared(Map<String, String> values, Set<String> keys, Set<String> required) {}

    /** 有界扫描（.env 深度 5；application 配置深度 8；跳过 .git/.svn/node_modules/target/build/dist/.idea/.gradle/.agent/worktrees/.mvn）。 */
    public static Declared scan(Path repoRoot);
}
```

规则（与代码一致）：

1. 候选文件：`.env`、`.env.example`、`.env.sample`、`.env.template`（含 `docker/.env*`）。
   排序：**非 example 的 `.env` 在前、`.env.example`/`sample`/`template` 在后**；同类按路径深度升序、再按字典序。
2. `values` 合并：按上述顺序 `putIfAbsent`，且**只接受非空值**（trim 后非空；去除成对单/双引号）。空值不占位、不覆盖非空值。
3. `keys`：所有声明过的 key（去重，含空值 key），用于绑定草案展示。
4. `required`：**entrypoint 的 `require` 与 compose 的 `${VAR:?}` 取并集**；
   只有二者都为空时才回退——`.env.example` 中空值 key，排除注释含 `可选/选填/非必填/optional/optinal`，再排除 `application*.yml` 中有非空默认值（`${KEY:default}`）的 key。
5. 最终 key 必须匹配 `[A-Za-z_][A-Za-z0-9_]*`；不得产出源码根本不存在的 key。

### 2. 网关侧（已实现）

- `EnvBindingService.requiredKeys(projectId)` / `sourceValues(projectId)` → 委托 `ProjectEnvFiles.scan(repo)`；不存在 `scanRequiredKeys` 方法。
- `EnvBindingService.draft`：`keys` 用 `declared.keys() ∪ required`（映射草案保持完整），`missing` 只统计 `required` 中“有效环境与仓库源码均无值”的 key，并写入 `effectiveHash`。
- `EnvironmentController.envCheck`：
  - 必填值判定：`envStore.effective().values()` 非空 **或** `sourceValues(projectId)` 非空 → 视为已配置；
  - 返回字段：`sourceProvidedVars`、`referencedCount`、`referencedRunningCount`、`middlewareHealthy`（= 被引用资源无 MISSING，未启动不算失败）；
  - `problems` 只保留：缺必填变量、被引用资源缺连接信息；未启动仍放 `stoppedResources`。
- `EnvironmentController.effective` / `resourceView`：每个资源增加 `referenced`；顶层增加 `referencedCount`、`referencedRunningCount`。资源还要求 `StackAdapter.declaredByProject(...)`，避免共享资源被算作本项目所需。
- `EnvQueryTools.env_summary` 的 `healthy` **仍只按本项目专属资源（`own`）统计**，尚未改成 `referenced` 口径（与前端不同）；统计口径待统一，属**未完成**项。

### 3. 执行层（已实现）

- `DockerEnvProvisioner.readDeclaredEnv` / `collectEnvFiles` 已删除，改为 `ProjectEnvFiles.scan(root).values()`；
  `seedFromProjectFiles` 保持“只写非空、写项目覆盖”的语义。
- `EnvProvisioner`（domain 接口）默认方法 `seedDeclared(...)` 返回空列表；`DockerEnvProvisioner` 实现为调用 `seedFromProjectFiles`。
- `BuiltInPipelineEngine.executeLocalDev`：在计算 `deployEnv` 之前调用 `seedDeclared(projectId, env)`，日志 `[env] 已按项目 .env 注入变量：...（值不回显）`，失败仅告警不阻断；随后 `localHostEnv(envStore.effective(...).values(), env)`。
  不新增容器创建（dev 仍走 `ensureRunning`，只启动已登记且被引用的资源）。

### 4. 前端（已实现）

- `platform/web/src/api/settings.ts`：`EffectiveEnvironmentView` 已含
  `referencedCount`、`referencedRunningCount`、资源项 `referenced: boolean`；
  `EnvCheckView` 已含 `sourceProvidedVars`、`referencedCount`、`referencedRunningCount`、`middlewareHealthy`。
- `components/pipeline/EnvSummary.vue`：
  - 健康分母 `referencedCount`，分子 `referencedRunningCount`；
  - 显示“所需中间件 x/y 运行”；有未启动时附“（N 个未启动，部署时自动启动）”，否则附“（另 N 个环境共享，不计入健康）”；
  - `missing` 仅统计被引用资源中 `status==='MISSING'` 的项。
- `components/settings/ProjectEnvPanel.vue`：
  - `runningCount` 分母改 `referencedCount`；`stoppedResources` 过滤 `referenced`；
  - 自检提示新增“源码已提供 …”（`sourceProvidedVars`）。

## 非目标

- 不修改项目仓库 `.env.example` / `entrypoint.sh`（平台需对任意 RuoYi 项目通用）。
- 不改动 pipeline.yml 的 `env-required` 定义，不改 E-509 的拦截时机。
- 不引入新的外部依赖。

## 验收标准

1. `RUOYIWNZ` 的 test/dev 自检不再报 `SMTP_USERNAME`、`RONGX_AES_KEY_PREVIOUS`、`RONGX_AES_IV_PREVIOUS` 缺失；
   `DB_HOST`/`REDIS_HOST`/`TOKEN_SECRET` 缺失时仍能正确报出。
2. 触发区与项目环境面板显示"所需中间件 x/y 运行"；仅启动项目引用的中间件后 `healthy=true`。
3. dev 实跑日志出现 `[env] 已按项目 .env 注入变量：...`，且注入集合包含 `.env` 中非空变量（如 `DB_PASSWORD`、`RONGX_AES_KEY`）。
4. 平台单测全绿；前端构建通过（2026-11-15 未重跑，仅确认用例存在）。

## 测试计划

- 已有 `platform/agent-domain/src/test/java/top/rongx/agent/domain/env/ProjectEnvFilesTest.java`：
  - fixture 临时目录构造 `docker/.env` + `docker/.env.example` + `docker/entrypoint.sh`；
  - 断言 `.env` 非空值优先、空值不覆盖、`required` 恰为 `DB_HOST/REDIS_HOST/TOKEN_SECRET`；
  - 无 entrypoint 时按注释“可选”与默认值排除。
- 既有 `DeployEnvPrecheckTest`、`DockerEnvProvisionerSeedTest` 覆盖预检与 seeding；网关自检字段没有独立控制器测试。
- 手工验收（历史记录，2026-11-15 未重跑）：重启平台后端后打开「设置 → 项目环境」，切 dev/test 检查自检；触发 dev 运行查看注入日志与健康摘要。
