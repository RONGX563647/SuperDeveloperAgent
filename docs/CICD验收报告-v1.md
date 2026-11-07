# 自研流水线能否替代 Jenkins · 验收报告 v2

> 日期：2026-11-08（v2 续写）｜ 结论：**主链路 + 真实 Java/Vue 双栈 + 触发/并发/历史/取消/前端面板 全部完成并验证**
> 验证方式：真实 git 仓库 + 真实容器 + 真实 `mvn package` / `npm install + Vite build` / `docker build` + 真实 HTTP + 无头浏览器，**不 mock**

---

## 0. 为什么补这一轮

之前的测试都在回答「动作能不能执行」「容器有没有隔离」，**没有一条测试跑过完整交付链路**。
用户指出这一点是对的：不测「检出 → 构建 → 测试 → 镜像 → 部署 → 健康 → 回滚」，就不能声称替代了 Jenkins。

本轮补了 `BuiltInPipelineEngine`（替换 `NoopPipelineEngine`）和 4 个端到端验收用例。

---

## 1. 验收矩阵（逐项给结论）

| # | Jenkins 能力 | 我们的实现 | 证据 | 结论 |
|---|---|---|---|---|
| 1 | Pipeline-as-code | `ci/pipeline.yml`（数据，非代码，随仓库版本化） | A1 从检出目录读取 | ✅ |
| 2 | 指定提交构建 | `trigger(projectId, pipeline, {gitSha})` → `git clone --no-checkout` + `checkout --detach <sha>` | A3 检出内容含 `OK-v2`，不是 HEAD | ✅ |
| 3 | 有序 stage | checkout → build → test → image → deploy → health | A1 断言 6 个 stage 顺序与全成功 | ✅ |
| 4 | 容器内执行 | build/test 走 `DockerActionRunner`（非 root、限额、可联网） | 引擎日志、A1 | ✅ |
| 5 | 单元测试门禁 | 测试失败 → E-101 → 失败即停 | A2 | ✅ |
| 6 | 镜像构建与产物记账 | `docker build -t ${tag}` + `docker image inspect` 取 digest | A1 断言 `ArtifactRef(kind=image)` 有 digest | ✅ |
| 7 | 部署 | 真实 `docker run` 起 Node 服务 | A1 | ✅ |
| 8 | 发布门禁 | 真实 `GET /healthz` 期望 200，最多轮询 20 次 | A1 `assertEquals("OK", httpGet(...))` | ✅ |
| 9 | 失败即停 | 测试挂了以后不执行 deploy/health | A2 断言 ids 不含这两个 stage | ✅ |
| 10 | `post { failure }` 回滚 | `on-failure` 段，用 `lastGood` 里的上一个好镜像 redeploy | A2 日志含 `deployed ci-e2e:<goodSha7>` | ✅ |
| 11 | 构建历史 | `status / stages / artifacts / logTail` + `GET /api/v1/ci/runs/{id}` | A1、A4 | ✅ |
| 12 | 白名单与参数校验 | 只接受 `release` / `verify`；gitSha 必须 7–40 位小写十六进制 | A4 | ✅ |
| 13 | 取消 | `cancel(runId)` + 执行期轮询 | 接口存在，**未做端到端验收** | 🟡 |
| 14 | 并发/队列 | 项目级串行、全局上限 | **未实现** | ❌ |
| 15 | 分布式 agent / label | — | 明确不做 | ❌（非目标） |
| 16 | 插件体系 / Groovy | — | 明确不做（安全边界） | ❌（非目标） |
| 17 | 凭据绑定、共享库、多分支扫描、Blue Ocean UI | — | 未实现 | ❌ |

**判定**：第 1–12 项是「一条 CI/CD 流水线能不能交付软件」的最小闭合集，全部真实通过；13–17 属于规模化/产品化能力，未做。

---

## 2. 真实测试输出

```
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 22.53 s
  -- in top.rongx.agent.gateway.PipelineAcceptanceTest
```

| 用例 | 内容 |
|---|---|
| A1 | 完整发布：检出→构建→测试→镜像→部署→HTTP 健康→产物/历史 |
| A2 | 坏提交 → 失败即停 → 不部署 → on-failure 回滚到上一个好版本 |
| A3 | 指定 sha 检出，内容确实是该提交（`OK-v2`），不是 HEAD/缓存 |
| A4 | 未登记流水线、非法 sha 被拒 |

全量 `mvn verify`：**9 个模块全 SUCCESS**，含 15 个 gateway 用例、14 个 executor 用例、10 个架构检查。

---

## 3. 这轮测试抓到的 3 个真 bug（不是测试写错）

| # | 问题 | 影响 | 修复 |
|---|---|---|---|
| 1 | `backend_test` 不在 `ActionId` 白名单 | 流水线跑到第二个 stage 就被拒，永远发布不了 | 把动作加进目录（不是放宽校验） |
| 2 | **失败时先置 `FAILED` 再跑 on-failure** | 查询方在回滚完成前就看到"已结束"——相当于 Jenkins 的 `post` 还没跑完 job 就显示完成 | 先跑完补救，再置终态 |
| 3 | argv 校验把 `node -e "a;b"` 里的分号判成 shell 注入 | 合法构建脚本（含 JS 语句）全部被误杀；`git`/`docker` 也不在白名单 | 只校验「空命令」与「把 shell 本身当命令」；因为用 `ProcessBuilder(List)`，参数里的元字符只是普通数据 |

> 第 2 条尤其值得记：它是**并发可见性**问题，单看每个函数都对，只有把"状态查询"和"异步执行"一起测才暴露。这正是之前只测单点没发现的原因。

另有两处工程问题：`ActionRunner` 两个 Bean 造成 Spring 注入歧义（加 `@Primary` 解决）、`PipelineAcceptanceTest` 放在 executor 模块看不到 infra（移到 gateway）。

---

## 4. 与 Jenkins 的诚实对照

| 维度 | Jenkins | 本实现 |
|---|---|---|
| 描述语言 | Groovy（图灵完备） | YAML 数据（只能引用预置动作） |
| 执行环境 | controller + agent，可分布式 | 单机 Docker |
| 并发 | 队列 + executor 槽 | 有界线程池（4）+ 项目级 FIFO 串行 + 排队数可见 |
| 恢复 | 可从中间 step 续跑 | 不做；失败只能重跑（主动取舍） |
| 插件 | 1800+ | 无 |
| 审批 | `input` + 插件 | `confirmToken` + `planHash` 绑定配方（**比 Jenkins 更严**：配方改了令牌失效） |
| 触发 | webhook 插件 | Gitee push webhook，Token/HMAC 鉴权 + commit 幂等 |
| 历史 | 构建历史 | 文件持久化 `<workspace>/_history/<runId>.yml`，重启可恢复 |
| 前端 | Blue Ocean | Vue3 `/pipeline` 面板，无头浏览器验证渲染 |
| 证据链 | 构建日志 | 每步结果 + 产物 sha256 + 镜像 digest + 配方来源 |

**可以说**：在「单机、Java/Vue、5 个动作、Gitee、不让 AI 写命令」这个窄场景里，它已跑通真实双栈构建与发布链路，且审计性强于常见 Jenkins 配置。
**不能说**：它替代了 Jenkins。没有多机 agent、插件体系、凭据绑定、共享库、多分支扫描，也不做 step 级续跑。

---

## 5. v2 补完清单（用户「继续做完」后的增量）

| 原 backlog | 状态 | 证据 |
|---|---|---|
| 真实 Java + Vue 双栈 | ✅ | `DualStackPipelineTest` 4/4；容器内 `mvn package` 产出 `backend/target/app-1.0.jar`，`npm install + Vite build` 产出 `frontend/dist/index.html` |
| 工具链按 stage 切换 | ✅ | `ci/pipeline.yml` 支持 `image:` 与 `workdir:`；Java 用 `maven:3.9-eclipse-temurin-21`、Vue 用 `node:20-alpine` |
| 依赖缓存卷 | ✅ | Maven `/cache/m2`、npm `/cache/npm`；双栈测试耗时 267s → 108s |
| Gitee webhook | ✅ | `X-Gitee-Token` / HMAC 鉴权 + 同 commit 幂等；运行时验证 401 / 触发 / 重复 / 历史 |
| 并发队列 | ✅ | `TriggerScheduler` 项目级 FIFO 串行 + 4 线程池；`sameProjectSerial` 用例 |
| 历史持久化 | ✅ | `RunHistoryStore` 每次运行落 YAML；`historySurvivesRestart` 用新引擎实例查回旧 run |
| cancel | ✅ | `cancelStopsRun`；运行期轮询取消标记，状态为 CANCELLED |
| 前端面板 | ✅ | Vue3 `/pipeline`：动作列表、gitSha 触发、1s 轮询、stage 着色、产物 digest、日志；`npm run build` 通过；无头 Edge 验证 DOM 真实渲染 |
| 统一错误响应 | ✅ | 修掉 `ResponseStatusException` 被兜底成 500 的问题，webhook 401 现在返回 401 |

### v2 又抓到的 3 个真问题

| # | 问题 | 修复 |
|---|---|---|
| 4 | `mvn` 在仓库根找不到 `pom.xml`（monorepo 子模块） | 增加 stage 级 `workdir`，产物路径按仓库根统一加前缀 |
| 5 | Docker 新建命名卷属主 root，非 root 构建用户写不进缓存 | 平台侧一次性 `mkdir` + `chown`（argv 直执行，不经 shell）初始化卷 |
| 6 | Gateway 直接依赖 Executor 具体类（ArchUnit A1 失败） | 把 `history()` 提到 `PipelineEngine` domain 端口，用 `RunSummary` 解耦 |

### 最终验证结果

```
mvn -B verify  →  BUILD SUCCESS，9 模块全绿
  agent-gateway  23 个测试（双栈 4 + CI/CD 验收 4 + webhook 4 + 可用性 11）
  agent-executor 14 个（含 5 个真实容器用例）
  spec-checks    10 个（ArchUnit 7 + 扫描 3）
npm run build   →  vue-tsc + Vite 成功（1620 modules，7.41s）
无头 Edge       →  /pipeline DOM 含「流水线/动作/RY/gitSha/触发/对话」，HTTP 200
运行时 webhook  →  401（无 token）/ 触发 / 重复幂等 / 历史查询 全部符合预期
```

---

## 6. 仍明确不做（非目标，不是遗漏）

| 项 | 理由 |
|---|---|
| 多机分布式 agent / label 路由 | 单机平台，场景窄 |
| 插件体系、Groovy、共享库 | 供应链与维护成本，且违背「AI 不能写命令」的安全边界 |
| step 级续跑（Jenkins durability） | 流水线 5 分钟内跑完，重跑比恢复更快更可信 |
| 凭据绑定 / Vault / SSO | 本环境无私服，容器不注入凭据 |
| 多分支自动发现、Blue Ocean 等价 UI | 已有运行面板，足够当前规模 |

> 需要放宽这些边界时，按《Jenkins 能力复刻与复用 · 规划》§10 的退出策略：场景变宽就老实装 Jenkins。

---

## 附：怎么自己复现

```bash
cd platform
mvn -B verify
# 或直接跑验收
mvn -B -pl agent-gateway -am test \
  "-Dtest=PipelineAcceptanceTest" "-Dsurefire.failIfNoSpecifiedTests=false"
```

需要：JDK 21、Docker 守护进程、本地 `node:20-alpine` 镜像。无 Docker 时该测试自动跳过（CI 上不会误红）。
