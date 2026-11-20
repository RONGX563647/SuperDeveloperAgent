<!-- 由 tools/docs/build.py 生成，勿手改；文档地图见 ../README.md -->
# 近期变更索引

留痕数据：`docs/ledger/*.jsonl`（185 个会话，截至 2026-11-20）。查历史用 `python tools/docs/find.py "<关键词>"`，不要整树通读。

## 最近 10 个会话

| 日期 | 类别 | 标题 | 模块 | 提交 |
|---|---|---|---|---|
| 2026-11-20 | chore | 防旧归档模型回潮：validate 拦截 docs/process 复活 | other | `dc587a0` |
| 2026-11-20 | chore | 合并 token/deploy 并行工作并移植门禁并发健壮性 | agent-domain, agent-gateway, agent-infra, agent-runtime, agent-tools, spec-checks, web | `e94171d` `3501d3b` `8145b76` `b9add58` `415b525` `3f97378` `b7aaa93` `43eb19c` `8139279` `35611ee` `c8aaf93` `d630a8c` `e6c9349` `363eccd` `a533133` `77b14fe` |
| 2026-11-20 | chore | 补齐 skill 路由层：三张新卡 + 生成式路由索引 | other | `3dc7dc1` |
| 2026-11-20 | chore | 合并 token 量化基线与部署矩阵修复到当前分支 | agent-gateway | `a533133` |
| 2026-11-20 | bug | 修复部署矩阵历史元数据与实际容器缝合 | agent-gateway | `3f97378` |
| 2026-11-20 | chore | 0.1→0.2 同步：新归档模型（ledger/views/skills）带入 0.2 线 | agent-executor, agent-gateway, web | `ceef158` `a163612` `813904b` `342bfd9` `352fc30` `bb27e58` `c1fc559` `c455510` `a27fe76` `2c4af50` `a381003` `df34598` `59f07ad` `bd18e87` `7296fc8` |
| 2026-11-20 | chore | 补周报视图，校正 README 阅读成本账 | other | `bd18e87` |
| 2026-11-20 | feat | 补齐 token 治理三条量化验收与插件分项归因 | agent-domain, agent-gateway, agent-infra, agent-runtime, spec-checks | `e94171d` |
| 2026-11-20 | feat | 会话能力页增加内置工具展示并改造界面与市场多源接入 | agent-domain, agent-gateway, agent-infra, agent-runtime, agent-tools, spec-checks, web | `a381003` `df34598` `59f07ad` `bd18e87` `7296fc8` `b9add58` `415b525` `43eb19c` `8139279` `35611ee` |
| 2026-11-20 | chore | docs 体系改造：ledger 单一写入 + skills/views/find 三层消费 | other | `a381003` |

## 模块索引

| 模块 | 会话数 | 最近变更 | 详见 |
|---|---|---|---|
| other | 41 | 2026-11-20 防旧归档模型回潮：validate 拦截 docs/process 复活 | [modules/other.md](modules/other.md) |
| agent-domain | 49 | 2026-11-20 合并 token/deploy 并行工作并移植门禁并发健壮性 | [modules/agent-domain.md](modules/agent-domain.md) |
| agent-gateway | 89 | 2026-11-20 合并 token/deploy 并行工作并移植门禁并发健壮性 | [modules/agent-gateway.md](modules/agent-gateway.md) |
| agent-infra | 52 | 2026-11-20 合并 token/deploy 并行工作并移植门禁并发健壮性 | [modules/agent-infra.md](modules/agent-infra.md) |
| agent-runtime | 32 | 2026-11-20 合并 token/deploy 并行工作并移植门禁并发健壮性 | [modules/agent-runtime.md](modules/agent-runtime.md) |
| agent-tools | 35 | 2026-11-20 合并 token/deploy 并行工作并移植门禁并发健壮性 | [modules/agent-tools.md](modules/agent-tools.md) |
| spec-checks | 26 | 2026-11-20 合并 token/deploy 并行工作并移植门禁并发健壮性 | [modules/spec-checks.md](modules/spec-checks.md) |
| web | 67 | 2026-11-20 合并 token/deploy 并行工作并移植门禁并发健壮性 | [modules/web.md](modules/web.md) |
| agent-executor | 54 | 2026-11-20 0.1→0.2 同步：新归档模型（ledger/views/skills）带入 0.2 线 | [modules/agent-executor.md](modules/agent-executor.md) |
| agent-governance | 14 | 2026-11-20 同步 0.1 分支到 0.2 分支（合并 + 重封归档链） | [modules/agent-governance.md](modules/agent-governance.md) |
| pom.xml | 3 | 2026-11-17 接入 springdoc、前端契约审计与错误码收敛 | [modules/pom-xml.md](modules/pom-xml.md) |
| README.md | 1 | 2026-11-17 会话级模型选择、确认卡续跑与多版本部署 | [modules/README-md.md](modules/README-md.md) |
