<!-- 由 tools/docs/build.py 生成，勿手改；文档地图见 ../README.md -->
# 近期变更索引

留痕数据：`docs/ledger/*.jsonl`（201 个会话，截至 2026-11-21）。查历史用 `python tools/docs/find.py "<关键词>"`，不要整树通读。

## 最近 10 个会话

| 日期 | 类别 | 标题 | 模块 | 提交 |
|---|---|---|---|---|
| 2026-11-21 | chore | 收敛并行会话的 0.2 修复线（规约违规平行修复 + 0.1 再入线 + 白屏修复） | agent-domain, agent-executor, agent-gateway, spec-checks, web | `f5c15a1` `b908869` `24906a0` `c1e3777` `f082fb5` `8398697` `20b85e4` `9cdd275` |
| 2026-11-21 | chore | 修复 refactor 线引入的 A1 分层违规与 EC5 通用 throw | agent-domain, agent-executor, agent-gateway | `7e02977` |
| 2026-11-21 | chore | 补录 0.1.1 已发布迁移 V5-V8 基线，恢复 pre-push 门禁 | spec-checks | `8398697` |
| 2026-11-21 | chore | 恢复 EncryptedAgentStateStore 的 handbook 锚点（refactor 重排时丢失） | agent-infra | `8d83f53` |
| 2026-11-21 | bug | BUG-22 部署成功后前端白屏：vendor 分包循环依赖 TDZ | web | `24906a0` `c1e3777` |
| 2026-11-21 | chore | 修复远端 refactor 引入的规约违规（A1 分层 / EC5 错误码 / R11 禁令） | agent-domain, agent-executor, agent-gateway | `b908869` |
| 2026-11-21 | chore | 合并 0.1.0-snapshot（沙箱 fail-closed 后移到执行期）并补录 0.2 refactor 线归档 | agent-domain, agent-executor, agent-gateway, agent-governance, agent-infra, agent-runtime, agent-tools, web | `d0aaa4d` `4bcbb32` `539d4ba` `dbfa7c2` `ca0a837` `e9e294a` `b6487a3` `781ba1f` `d4320a7` `cd05f58` |
| 2026-11-21 | chore | 补归档：远端 0.2 八条 refactor 提交并入 0.1 修复收敛 | agent-domain, agent-executor, agent-gateway, agent-governance, agent-infra, agent-runtime, agent-tools, web | `d0aaa4d` `4bcbb32` `539d4ba` `dbfa7c2` `ca0a837` `e9e294a` `b6487a3` `781ba1f` `d4320a7` `cd05f58` |
| 2026-11-21 | bug | 修复部署 health 失败：沙箱 fail-closed 从启动期后移到执行期 | agent-executor | `d4320a7` |
| 2026-11-21 | chore | 合并后重算迁移基线（V7/V8 补登记） | spec-checks | `2358d08` |

## 模块索引

| 模块 | 会话数 | 最近变更 | 详见 |
|---|---|---|---|
| agent-domain | 56 | 2026-11-21 收敛并行会话的 0.2 修复线（规约违规平行修复 + 0.1 再入线 + 白屏修复） | [modules/agent-domain.md](modules/agent-domain.md) |
| agent-executor | 62 | 2026-11-21 收敛并行会话的 0.2 修复线（规约违规平行修复 + 0.1 再入线 + 白屏修复） | [modules/agent-executor.md](modules/agent-executor.md) |
| agent-gateway | 100 | 2026-11-21 收敛并行会话的 0.2 修复线（规约违规平行修复 + 0.1 再入线 + 白屏修复） | [modules/agent-gateway.md](modules/agent-gateway.md) |
| spec-checks | 31 | 2026-11-21 收敛并行会话的 0.2 修复线（规约违规平行修复 + 0.1 再入线 + 白屏修复） | [modules/spec-checks.md](modules/spec-checks.md) |
| web | 75 | 2026-11-21 收敛并行会话的 0.2 修复线（规约违规平行修复 + 0.1 再入线 + 白屏修复） | [modules/web.md](modules/web.md) |
| agent-infra | 58 | 2026-11-21 恢复 EncryptedAgentStateStore 的 handbook 锚点（refactor 重排时丢失） | [modules/agent-infra.md](modules/agent-infra.md) |
| agent-governance | 16 | 2026-11-21 合并 0.1.0-snapshot（沙箱 fail-closed 后移到执行期）并补录 0.2 refactor 线归档 | [modules/agent-governance.md](modules/agent-governance.md) |
| agent-runtime | 36 | 2026-11-21 合并 0.1.0-snapshot（沙箱 fail-closed 后移到执行期）并补录 0.2 refactor 线归档 | [modules/agent-runtime.md](modules/agent-runtime.md) |
| agent-tools | 38 | 2026-11-21 合并 0.1.0-snapshot（沙箱 fail-closed 后移到执行期）并补录 0.2 refactor 线归档 | [modules/agent-tools.md](modules/agent-tools.md) |
| other | 41 | 2026-11-20 防旧归档模型回潮：validate 拦截 docs/process 复活 | [modules/other.md](modules/other.md) |
| pom.xml | 3 | 2026-11-17 接入 springdoc、前端契约审计与错误码收敛 | [modules/pom-xml.md](modules/pom-xml.md) |
| README.md | 1 | 2026-11-17 会话级模型选择、确认卡续跑与多版本部署 | [modules/README-md.md](modules/README-md.md) |
