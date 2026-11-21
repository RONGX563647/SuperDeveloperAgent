<!-- 由 tools/docs/build.py 生成，勿手改；文档地图见 ../README.md -->
# 近期变更索引

留痕数据：`docs/ledger/*.jsonl`（196 个会话，截至 2026-11-21）。查历史用 `python tools/docs/find.py "<关键词>"`，不要整树通读。

## 最近 10 个会话

| 日期 | 类别 | 标题 | 模块 | 提交 |
|---|---|---|---|---|
| 2026-11-21 | chore | 修复 refactor 线引入的 A1 分层违规与 EC5 通用 throw | agent-domain, agent-executor, agent-gateway | `7e02977` |
| 2026-11-21 | chore | 恢复 EncryptedAgentStateStore 的 handbook 锚点（refactor 重排时丢失） | agent-infra | `8d83f53` |
| 2026-11-21 | chore | 合并 0.1.0-snapshot（沙箱 fail-closed 后移到执行期）并补录 0.2 refactor 线归档 | agent-domain, agent-executor, agent-gateway, agent-governance, agent-infra, agent-runtime, agent-tools, web | `d0aaa4d` `4bcbb32` `539d4ba` `dbfa7c2` `ca0a837` `e9e294a` `b6487a3` `781ba1f` `d4320a7` `cd05f58` |
| 2026-11-21 | bug | 修复部署 health 失败：沙箱 fail-closed 从启动期后移到执行期 | agent-executor | `d4320a7` |
| 2026-11-21 | chore | 合并后重算迁移基线（V7/V8 补登记） | spec-checks | `2358d08` |
| 2026-11-21 | bug | 沙箱与 Docker 机制缺陷修复与重复实现收敛 | agent-domain, agent-executor, agent-gateway, agent-infra, spec-checks | `e427338` `7c654cf` |
| 2026-11-20 | bug | 修复固化脚本重放路径丢失 version/tag 导致打 tag 未生效 | agent-executor, agent-gateway, web | `a2aa87e` |
| 2026-11-20 | chore | 合并 0.1 线到 0.2 线并解决台账链冲突 | agent-gateway, web | `864e974` `426e75d` `f584dd7` |
| 2026-11-20 | chore | 0.1→0.2 同步：token/deploy 工作 + skills 路由 + 并发健壮性入线 | agent-domain, agent-gateway, agent-infra, agent-runtime, agent-tools, spec-checks, web | `e94171d` `3501d3b` `8145b76` `b9add58` `415b525` `3f97378` `b7aaa93` `43eb19c` `8139279` `35611ee` `c8aaf93` `d630a8c` `e6c9349` `363eccd` `a533133` `77b14fe` `3dc7dc1` `b39e594` `737dd49` `dc587a0` `2d470d6` |
| 2026-11-20 | chore | 防旧归档模型回潮：validate 拦截 docs/process 复活 | other | `dc587a0` |

## 模块索引

| 模块 | 会话数 | 最近变更 | 详见 |
|---|---|---|---|
| agent-domain | 53 | 2026-11-21 修复 refactor 线引入的 A1 分层违规与 EC5 通用 throw | [modules/agent-domain.md](modules/agent-domain.md) |
| agent-executor | 59 | 2026-11-21 修复 refactor 线引入的 A1 分层违规与 EC5 通用 throw | [modules/agent-executor.md](modules/agent-executor.md) |
| agent-gateway | 97 | 2026-11-21 修复 refactor 线引入的 A1 分层违规与 EC5 通用 throw | [modules/agent-gateway.md](modules/agent-gateway.md) |
| agent-infra | 57 | 2026-11-21 恢复 EncryptedAgentStateStore 的 handbook 锚点（refactor 重排时丢失） | [modules/agent-infra.md](modules/agent-infra.md) |
| agent-governance | 15 | 2026-11-21 合并 0.1.0-snapshot（沙箱 fail-closed 后移到执行期）并补录 0.2 refactor 线归档 | [modules/agent-governance.md](modules/agent-governance.md) |
| agent-runtime | 35 | 2026-11-21 合并 0.1.0-snapshot（沙箱 fail-closed 后移到执行期）并补录 0.2 refactor 线归档 | [modules/agent-runtime.md](modules/agent-runtime.md) |
| agent-tools | 37 | 2026-11-21 合并 0.1.0-snapshot（沙箱 fail-closed 后移到执行期）并补录 0.2 refactor 线归档 | [modules/agent-tools.md](modules/agent-tools.md) |
| web | 72 | 2026-11-21 合并 0.1.0-snapshot（沙箱 fail-closed 后移到执行期）并补录 0.2 refactor 线归档 | [modules/web.md](modules/web.md) |
| spec-checks | 29 | 2026-11-21 合并后重算迁移基线（V7/V8 补登记） | [modules/spec-checks.md](modules/spec-checks.md) |
| other | 41 | 2026-11-20 防旧归档模型回潮：validate 拦截 docs/process 复活 | [modules/other.md](modules/other.md) |
| pom.xml | 3 | 2026-11-17 接入 springdoc、前端契约审计与错误码收敛 | [modules/pom-xml.md](modules/pom-xml.md) |
| README.md | 1 | 2026-11-17 会话级模型选择、确认卡续跑与多版本部署 | [modules/README-md.md](modules/README-md.md) |
