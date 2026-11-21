<!-- 由 tools/docs/build.py 生成，勿手改；文档地图见 ../README.md -->
# 近期变更索引

留痕数据：`docs/ledger/*.jsonl`（182 个会话，截至 2026-11-21）。查历史用 `python tools/docs/find.py "<关键词>"`，不要整树通读。

## 最近 10 个会话

| 日期 | 类别 | 标题 | 模块 | 提交 |
|---|---|---|---|---|
| 2026-11-21 | bug | 修复部署 health 失败：沙箱 fail-closed 从启动期后移到执行期 | agent-executor | `d4320a7` |
| 2026-11-21 | bug | 沙箱与 Docker 机制缺陷修复与重复实现收敛 | agent-domain, agent-executor, agent-gateway, agent-infra, spec-checks | `e427338` `7c654cf` |
| 2026-11-20 | bug | 修复固化脚本重放路径丢失 version/tag 导致打 tag 未生效 | agent-executor, agent-gateway, web | `a2aa87e` |
| 2026-11-20 | chore | 防旧归档模型回潮：validate 拦截 docs/process 复活 | other | `dc587a0` |
| 2026-11-20 | chore | 合并 token/deploy 并行工作并移植门禁并发健壮性 | agent-domain, agent-gateway, agent-infra, agent-runtime, agent-tools, spec-checks, web | `e94171d` `3501d3b` `8145b76` `b9add58` `415b525` `3f97378` `b7aaa93` `43eb19c` `8139279` `35611ee` `c8aaf93` `d630a8c` `e6c9349` `363eccd` `a533133` `77b14fe` |
| 2026-11-20 | chore | 补齐 skill 路由层：三张新卡 + 生成式路由索引 | other | `3dc7dc1` |
| 2026-11-20 | chore | 合并 token 量化基线与部署矩阵修复到当前分支 | agent-gateway | `a533133` |
| 2026-11-20 | bug | 任务版本号选择：建议值进下拉并按类型给目标版本 | agent-gateway, web | `864e974` |
| 2026-11-20 | feat | 对话层迁移到 AgentScope 官方 HarnessAgent | agent-gateway, agent-infra, agent-runtime | `24b81db` |
| 2026-11-20 | bug | 修复部署矩阵历史元数据与实际容器缝合 | agent-gateway | `3f97378` |

## 模块索引

| 模块 | 会话数 | 最近变更 | 详见 |
|---|---|---|---|
| agent-executor | 54 | 2026-11-21 修复部署 health 失败：沙箱 fail-closed 从启动期后移到执行期 | [modules/agent-executor.md](modules/agent-executor.md) |
| agent-domain | 47 | 2026-11-21 沙箱与 Docker 机制缺陷修复与重复实现收敛 | [modules/agent-domain.md](modules/agent-domain.md) |
| agent-gateway | 86 | 2026-11-21 沙箱与 Docker 机制缺陷修复与重复实现收敛 | [modules/agent-gateway.md](modules/agent-gateway.md) |
| agent-infra | 51 | 2026-11-21 沙箱与 Docker 机制缺陷修复与重复实现收敛 | [modules/agent-infra.md](modules/agent-infra.md) |
| spec-checks | 25 | 2026-11-21 沙箱与 Docker 机制缺陷修复与重复实现收敛 | [modules/spec-checks.md](modules/spec-checks.md) |
| web | 65 | 2026-11-20 修复固化脚本重放路径丢失 version/tag 导致打 tag 未生效 | [modules/web.md](modules/web.md) |
| other | 40 | 2026-11-20 防旧归档模型回潮：validate 拦截 docs/process 复活 | [modules/other.md](modules/other.md) |
| agent-runtime | 31 | 2026-11-20 合并 token/deploy 并行工作并移植门禁并发健壮性 | [modules/agent-runtime.md](modules/agent-runtime.md) |
| agent-tools | 33 | 2026-11-20 合并 token/deploy 并行工作并移植门禁并发健壮性 | [modules/agent-tools.md](modules/agent-tools.md) |
| agent-governance | 13 | 2026-11-20 0.1.1 OPEN bug 批量修复（27+3 条） | [modules/agent-governance.md](modules/agent-governance.md) |
| pom.xml | 3 | 2026-11-17 接入 springdoc、前端契约审计与错误码收敛 | [modules/pom-xml.md](modules/pom-xml.md) |
| README.md | 1 | 2026-11-17 会话级模型选择、确认卡续跑与多版本部署 | [modules/README-md.md](modules/README-md.md) |
