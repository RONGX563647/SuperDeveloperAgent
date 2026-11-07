# 自研流水线能否替代 Jenkins · 验收报告

> 日期：2026-11-08 ｜ 结论：**已通过 CI/CD 主链路验收（4/4），但只是「窄版可替代」，不是完整 Jenkins**
> 验证方式：真实 git 仓库 + 真实容器 + 真实 `docker build` + 真实 HTTP 服务，**不 mock**

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
| 并发 | 队列 + executor 槽 | 无（一次一个 run 异步跑） |
| 恢复 | 可从中间 step 续跑 | 不做；失败只能重跑 |
| 插件 | 1800+ | 无 |
| 审批 | `input` + 插件 | `confirmToken` + `planHash` 绑定配方（**比 Jenkins 更严**：配方改了令牌失效） |
| 证据链 | 构建日志 | 每步结果 + 产物 sha256 + 镜像 digest + 配方来源 |

**可以说**：在「单机、Java/Vue、5 个动作、Gitee、不让 AI 写命令」这个窄场景里，它已经能完成一次真实发布，且审计性强于常见 Jenkins 配置。
**不能说**：它替代了 Jenkins。没有队列、没有多机、没有插件、没有 UI、没有凭据体系、没有真实项目的 Maven/Gradle 构建（验收用的是 Node fixture）。

---

## 5. 仍未验证 / 下一步

| 优先级 | 事项 |
|---|---|
| P0 | 用**真实 Java + Vue 项目**（`mvn package` / `pnpm build`）跑同一条流水线——现在的验收是 Node fixture，工具链不同 |
| P0 | 接 Gitee webhook 自动触发（现在是手动 `trigger`） |
| P1 | 并发队列 + 项目级串行；构建历史落库（现在是内存 Map，重启即丢） |
| P1 | `cancel` 端到端验收；凭据不进容器前提下挂载只读 `settings.xml` |
| P2 | 前端运行面板（现在只有 REST 接口） |

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
