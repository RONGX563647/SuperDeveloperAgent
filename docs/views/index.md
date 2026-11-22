<!-- 由 tools/docs/build.py 生成，勿手改；文档地图见 ../README.md -->
# 近期变更索引

留痕数据：`docs/ledger/*.jsonl`（209 个会话，截至 2026-11-22）。查历史用 `python tools/docs/find.py "<关键词>"`，不要整树通读。

## 最近 10 个会话

| 日期 | 类别 | 标题 | 模块 | 提交 |
|---|---|---|---|---|
| 2026-11-22 | chore | 合并 0.1 的 CI 修复（前端契约/jackson 漏洞/PG 回退）到 0.2 | agent-gateway, web | `f2fe649` `24eceb9` `9f054d9` `3e1d20f` |
| 2026-11-22 | bug | 修复 CI backend：真 PG 热切换回退时 Flyway 重放 V7 drop app_url 失败 | agent-gateway | `9f054d9` |
| 2026-11-22 | chore | 修复 CI：前端 schema.d.ts 契约同步 + jackson 2.21.1 安全漏洞升级到 2.21.4 | web | `f2fe649` |
| 2026-11-22 | chore | 合并 0.1.0-snapshot 到 0.2.0-snapshot（移除 prod sha 校验 + 重放打 tag 分支透传 + SERVER 网络/账本口径修复） | agent-domain, agent-executor, agent-gateway, web | `ee4bc36` `9a87b34` `0ee8f9b` `70c2051` `e9de11b` `3a48994` |
| 2026-11-22 | bug | 将生产 SERVER 部署网络与镜像账本口径修复移植到 0.2 分支 | agent-executor | `011511e` |
| 2026-11-22 | bug | 移除 prod 晋升的提交一致性校验，放行重放/打 tag 场景 | agent-domain, agent-executor | `e9de11b` |
| 2026-11-21 | bug | 修复生产 SERVER 部署远端网络缺失与镜像元数据账本口径 | agent-executor | `0ee8f9b` |
| 2026-11-21 | chore | 收敛并行会话的 0.2 修复线（规约违规平行修复 + 0.1 再入线 + 白屏修复） | agent-domain, agent-executor, agent-gateway, spec-checks, web | `f5c15a1` `b908869` `24906a0` `c1e3777` `f082fb5` `8398697` `20b85e4` `9cdd275` |
| 2026-11-21 | bug | BUG-8 重放打 tag 失败：branch 未透传到引擎 | agent-gateway, web | `ee4bc36` |
| 2026-11-21 | chore | 修复 refactor 线引入的 A1 分层违规与 EC5 通用 throw | agent-domain, agent-executor, agent-gateway | `7e02977` |

## 模块索引

| 模块 | 会话数 | 最近变更 | 详见 |
|---|---|---|---|
| agent-gateway | 104 | 2026-11-22 合并 0.1 的 CI 修复（前端契约/jackson 漏洞/PG 回退）到 0.2 | [modules/agent-gateway.md](modules/agent-gateway.md) |
| web | 79 | 2026-11-22 合并 0.1 的 CI 修复（前端契约/jackson 漏洞/PG 回退）到 0.2 | [modules/web.md](modules/web.md) |
| agent-domain | 58 | 2026-11-22 合并 0.1.0-snapshot 到 0.2.0-snapshot（移除 prod sha 校验 + 重放打 tag 分支透传 + SERVER 网络/账本口径修复） | [modules/agent-domain.md](modules/agent-domain.md) |
| agent-executor | 66 | 2026-11-22 合并 0.1.0-snapshot 到 0.2.0-snapshot（移除 prod sha 校验 + 重放打 tag 分支透传 + SERVER 网络/账本口径修复） | [modules/agent-executor.md](modules/agent-executor.md) |
| spec-checks | 31 | 2026-11-21 收敛并行会话的 0.2 修复线（规约违规平行修复 + 0.1 再入线 + 白屏修复） | [modules/spec-checks.md](modules/spec-checks.md) |
| agent-infra | 58 | 2026-11-21 恢复 EncryptedAgentStateStore 的 handbook 锚点（refactor 重排时丢失） | [modules/agent-infra.md](modules/agent-infra.md) |
| agent-governance | 16 | 2026-11-21 合并 0.1.0-snapshot（沙箱 fail-closed 后移到执行期）并补录 0.2 refactor 线归档 | [modules/agent-governance.md](modules/agent-governance.md) |
| agent-runtime | 36 | 2026-11-21 合并 0.1.0-snapshot（沙箱 fail-closed 后移到执行期）并补录 0.2 refactor 线归档 | [modules/agent-runtime.md](modules/agent-runtime.md) |
| agent-tools | 38 | 2026-11-21 合并 0.1.0-snapshot（沙箱 fail-closed 后移到执行期）并补录 0.2 refactor 线归档 | [modules/agent-tools.md](modules/agent-tools.md) |
| other | 41 | 2026-11-20 防旧归档模型回潮：validate 拦截 docs/process 复活 | [modules/other.md](modules/other.md) |
| pom.xml | 3 | 2026-11-17 接入 springdoc、前端契约审计与错误码收敛 | [modules/pom-xml.md](modules/pom-xml.md) |
| README.md | 1 | 2026-11-17 会话级模型选择、确认卡续跑与多版本部署 | [modules/README-md.md](modules/README-md.md) |
