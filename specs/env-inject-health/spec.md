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

## 现状定位（已读代码）

| 位置 | 现状 | 问题 |
| --- | --- | --- |
| `platform/agent-gateway/.../settings/EnvBindingService.java` `scanRequiredKeys` | 读取 `.env.example` 全部 key，返回即必填 | 可选/有默认值的变量被误判必填；自检把结果用于 `envCheck` |
| `platform/agent-gateway/.../web/EnvironmentController.java` `envCheck` | 只对照 `envStore.effective` 的值 | 没考虑仓库源码里的值；`stoppedResources` 只展示不参与 ok，但前端展示为"未运行" |
| `EnvironmentController.effective` / `resourceView` | 返回全部环境资源 | 未标记"是否被本项目引用"，前端只能按总数算健康 |
| `platform/agent-executor/.../DockerEnvProvisioner.java` `readDeclaredEnv` | 固定候选 + 深度遍历，`putIfAbsent` 合并，空值不覆盖 | 文件遍历顺序不定，`.env.example` 可能先占位，导致 `.env` 的同名非空值不生效（"源码有值但没注入"） |
| `BuiltInPipelineEngine.executeLocalDev` | dev 只用 `envStore.effective` 注入，`ensureRunning` 只启动已登记资源 | dev 从不执行 `seedFromProjectFiles`，仓库 `.env` 的值不会注入本机前后端 |
| `platform/web/src/components/pipeline/EnvSummary.vue`、`components/settings/ProjectEnvPanel.vue` | `running/total` 用全部 resources | 把共享/未引用资源计入健康分母 |
| 仓库 `docker/entrypoint.sh` | 有权威必填声明：`require DB_HOST`、`require REDIS_HOST`、`require TOKEN_SECRET` | 平台没有利用这个事实来源 |

## 目标

- R1 自检只把**项目真正必需**的变量判为必填；`.env.example` 中有默认值、或注释标注可选的变量不再拦截。
- R2 仓库 `.env` 的非空值必须被稳定采集并注入（`.env` 优先于 `.env.example`，空值不得覆盖非空值）；dev 本机部署同样注入。
- R3 中间件健康只看"被项目引用的资源"；未启动不算异常（部署时会启动），未被引用不计入健康分母。
- R4 不改变现有 E-509 / E-701 / E-702 的失败语义，只收紧判定口径。
- R5 有单测覆盖；实跑验收通过。

## 方案

### 1. 新增共享解析器（domain 层，避免网关/执行层两套实现）

新文件 `platform/agent-domain/src/main/java/top/rongx/agent/domain/env/ProjectEnvFiles.java`（纯静态工具，无 Spring 依赖）：

```java
public final class ProjectEnvFiles {
    public record Declared(Map<String, String> values, Set<String> keys, Set<String> required) {}

    /** 有界扫描（深度 5，跳过 .git/node_modules/target/build/.idea/.gradle/.agent/worktrees）。 */
    public static Declared scan(Path repoRoot);
}
```

规则（decision-complete）：

1. 候选文件：仓库根及子目录下的 `.env`、`.env.example`（含 `docker/.env*`）。
   排序：**文件名 `.env` 在前、`.env.example` 在后**；同类按路径深度升序、再按字典序，保证结果与文件系统遍历顺序无关。
2. `values` 合并：按上述顺序 `putIfAbsent`，且**只接受非空值**（trim 后非空；去除成对单/双引号）。空值不得占据 key。
3. `keys`：所有声明过的 key（去重，含空值 key），用于绑定草案展示。
4. `required` 判定优先级：
   1. 若存在 `entrypoint.sh`（深度 ≤5，优先 `docker/` 目录）：解析 `require\s+([A-Za-z_][A-Za-z0-9_]*)` 行 → 这些即必填；
   2. 否则解析 `docker-compose*.yml` 中 `${VAR:?` 形式 → 必填；
   3. 否则回退：`.env.example` 中**空值**的 key，排除「上一行/同段注释块含 `可选`、`optional`、`选填`」，再排除在 `application*.yml` 中有非空默认值（`${KEY:default}`）的 key。
5. 合并 `docker-compose` / `entrypoint.sh` 中**同时出现**的必填项并集；不得产出源码根本不存在的 key。

### 2. 网关侧改造

- `EnvBindingService.scanRequiredKeys` → 委托 `ProjectEnvFiles.scan(repo).required()`。
- `EnvBindingService.draft`：`keys` 仍用 `declared.keys()`（映射草案保持完整），`missing` 只统计 `required` 中无值（有效环境或仓库源码均无值）的 key。
- `EnvironmentController.envCheck`：
  - 必填值判定：`envStore.effective().values()` 非空 **或** `ProjectEnvFiles.scan(repo).values()` 非空 → 视为已配置；
  - 新增返回字段：`sourceProvidedVars`（仅由源码提供值的必填变量名）、`referencedCount`、`referencedRunningCount`、`middlewareHealthy`（= 被引用资源无 MISSING，且未启动不算失败）；
  - `problems` 只保留：缺必填变量（按上述判定）、被引用资源缺连接信息；未启动仍放 `stoppedResources`，不进 `problems`。
- `EnvironmentController.effective` / `resourceView`：
  - 每个资源增加 `referenced` 布尔（`StackAdapter.isReferenced(r, effective.values())`）；
  - 顶层增加 `referencedCount`、`referencedRunningCount`。
- 中间件 helper 供 `EnvTools.env_summary` 复用：`healthy` 只按被引用资源计算（已有 `own` 口径，补充 referenced 过滤，避免未引用资源拉低健康）。

### 3. 执行层改造

- `DockerEnvProvisioner.readDeclaredEnv` / `collectEnvFiles` 删除，改为 `ProjectEnvFiles.scan(root).values()`；
  `seedFromProjectFiles` 保持"只写非空、写项目覆盖"的语义。
- `EnvProvisioner`（domain 接口）增加默认方法：
  ```java
  default List<String> seedDeclared(String projectId, String env) { return List.of(); }
  ```
  `DockerEnvProvisioner` 实现为调用 `seedFromProjectFiles`；`noop()` 保持空实现。
- `BuiltInPipelineEngine.executeLocalDev`：在计算 `deployEnv` 之前调用（`envProvisioner != null` 时）：
  `seedDeclared(projectId, env)`，把返回值写日志 `[env] 已按项目 .env 注入变量：...（值不回显）`；
  然后再 `localHostEnv(envStore.effective(...).values(), env)`。
  不新增容器创建（dev 仍走 `ensureRunning`，只启动已登记且被引用的资源）。

### 4. 前端改造

- `platform/web/src/api/settings.ts`：`EffectiveEnvironmentView` 增加
  `referencedCount: number`、`referencedRunningCount: number`、资源项增加 `referenced: boolean`；
  `EnvCheckView` 增加 `sourceProvidedVars: string[]`、`referencedCount?`、`referencedRunningCount?`、`middlewareHealthy?: boolean`。
- `components/pipeline/EnvSummary.vue`：
  - 健康分母改为 `referencedCount`，分子 `referencedRunningCount`；
  - 未引用资源不参与显示（或折叠为"环境共享 N 个，不计入健康"）；
  - `missing` 仅统计 `referenced && status==='MISSING'`；`stopped` 只统计被引用资源，文案保持"部署时自动启动"。
- `components/settings/ProjectEnvPanel.vue`：
  - `runningCount` 分母改 `referencedCount`；`stoppedResources` 过滤 `referenced`；
  - 自检提示中新增："以下变量取自源码，无需填写：..."（`sourceProvidedVars`）。

## 非目标

- 不修改项目仓库 `.env.example` / `entrypoint.sh`（平台需对任意 RuoYi 项目通用）。
- 不改动 pipeline.yml 的 `env-required` 定义，不改 E-509 的拦截时机。
- 不引入新的外部依赖。

## 验收标准

1. `RUOYIWNZ` 的 test/dev 自检不再报 `SMTP_USERNAME`、`RONGX_AES_KEY_PREVIOUS`、`RONGX_AES_IV_PREVIOUS` 缺失；
   `DB_HOST`/`REDIS_HOST`/`TOKEN_SECRET` 缺失时仍能正确报出。
2. 触发区与项目环境面板显示"所需中间件 x/y 运行"；仅启动项目引用的中间件后 `healthy=true`。
3. dev 实跑日志出现 `[env] 已按项目 .env 注入变量：...`，且注入集合包含 `.env` 中非空变量（如 `DB_PASSWORD`、`RONGX_AES_KEY`）。
4. 平台单测全绿；前端构建通过。

## 测试计划

- 新增 `platform/agent-domain/src/test/java/top/rongx/agent/domain/env/ProjectEnvFilesTest.java`：
  - fixture 临时目录构造 `docker/.env` + `docker/.env.example` + `docker/entrypoint.sh`；
  - 断言 `.env` 非空值优先、空值不覆盖、`required` 恰为 `DB_HOST/REDIS_HOST/TOKEN_SECRET`；
  - 无 entrypoint 时按注释"可选"与默认值排除。
- 既有 `DeployEnvPrecheckTest`、`DockerEnvProvisionerTest`（如有）保持通过；必要时补充 seeding 断言。
- 手工验收：重启平台后端后打开「设置 → 项目环境」，切 dev/test 检查自检；触发 dev 运行查看注入日志与健康摘要。
