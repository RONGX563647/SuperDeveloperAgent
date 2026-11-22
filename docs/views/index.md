<!-- 由 tools/docs/build.py 生成，勿手改；文档地图见 ../README.md -->
# 近期变更索引

留痕数据：`docs/ledger/*.jsonl`（189 个会话，截至 2026-11-22）。查历史用 `python tools/docs/find.py "<关键词>"`，不要整树通读。

## 最近 10 个会话

| 日期 | 类别 | 标题 | 模块 | 提交 |
|---|---|---|---|---|
| 2026-11-22 | bug | 修复 CI backend：真 PG 热切换回退时 Flyway 重放 V7 drop app_url 失败 | agent-gateway | `9f054d9` |
| 2026-11-22 | chore | 修复 CI：前端 schema.d.ts 契约同步 + jackson 2.21.1 安全漏洞升级到 2.21.4 | web | `f2fe649` |
| 2026-11-22 | bug | 移除 prod 晋升的提交一致性校验，放行重放/打 tag 场景 | agent-domain, agent-executor | `e9de11b` |
| 2026-11-21 | bug | 修复生产 SERVER 部署远端网络缺失与镜像元数据账本口径 | agent-executor | `0ee8f9b` |
| 2026-11-21 | bug | BUG-8 重放打 tag 失败：branch 未透传到引擎 | agent-gateway, web | `ee4bc36` |
| 2026-11-21 | chore | 补录 0.1.1 已发布迁移 V5-V8 基线，恢复 pre-push 门禁 | spec-checks | `8398697` |
| 2026-11-21 | bug | BUG-22 部署成功后前端白屏：vendor 分包循环依赖 TDZ | web | `24906a0` `c1e3777` |
| 2026-11-21 | bug | 修复部署 health 失败：沙箱 fail-closed 从启动期后移到执行期 | agent-executor | `d4320a7` |
| 2026-11-21 | bug | 沙箱与 Docker 机制缺陷修复与重复实现收敛 | agent-domain, agent-executor, agent-gateway, agent-infra, spec-checks | `e427338` `7c654cf` |
| 2026-11-20 | bug | 修复固化脚本重放路径丢失 version/tag 导致打 tag 未生效 | agent-executor, agent-gateway, web | `a2aa87e` |

## 模块索引

| 模块 | 会话数 | 最近变更 | 详见 |
|---|---|---|---|
| agent-gateway | 88 | 2026-11-22 修复 CI backend：真 PG 热切换回退时 Flyway 重放 V7 drop app_url 失败 | [modules/agent-gateway.md](modules/agent-gateway.md) |
| web | 68 | 2026-11-22 修复 CI：前端 schema.d.ts 契约同步 + jackson 2.21.1 安全漏洞升级到 2.21.4 | [modules/web.md](modules/web.md) |
| agent-domain | 48 | 2026-11-22 移除 prod 晋升的提交一致性校验，放行重放/打 tag 场景 | [modules/agent-domain.md](modules/agent-domain.md) |
| agent-executor | 56 | 2026-11-22 移除 prod 晋升的提交一致性校验，放行重放/打 tag 场景 | [modules/agent-executor.md](modules/agent-executor.md) |
| spec-checks | 26 | 2026-11-21 补录 0.1.1 已发布迁移 V5-V8 基线，恢复 pre-push 门禁 | [modules/spec-checks.md](modules/spec-checks.md) |
| agent-infra | 51 | 2026-11-21 沙箱与 Docker 机制缺陷修复与重复实现收敛 | [modules/agent-infra.md](modules/agent-infra.md) |
| other | 40 | 2026-11-20 防旧归档模型回潮：validate 拦截 docs/process 复活 | [modules/other.md](modules/other.md) |
| agent-runtime | 31 | 2026-11-20 合并 token/deploy 并行工作并移植门禁并发健壮性 | [modules/agent-runtime.md](modules/agent-runtime.md) |
| agent-tools | 33 | 2026-11-20 合并 token/deploy 并行工作并移植门禁并发健壮性 | [modules/agent-tools.md](modules/agent-tools.md) |
| agent-governance | 13 | 2026-11-20 0.1.1 OPEN bug 批量修复（27+3 条） | [modules/agent-governance.md](modules/agent-governance.md) |
| pom.xml | 3 | 2026-11-17 接入 springdoc、前端契约审计与错误码收敛 | [modules/pom-xml.md](modules/pom-xml.md) |
| README.md | 1 | 2026-11-17 会话级模型选择、确认卡续跑与多版本部署 | [modules/README-md.md](modules/README-md.md) |
