<!-- 由 tools/docs/build.py 生成，勿手改；文档地图见 ../README.md -->
# 近期变更索引

留痕数据：`docs/ledger/*.jsonl`（171 个会话，截至 2026-11-20）。查历史用 `python tools/docs/find.py "<关键词>"`，不要整树通读。

## 最近 10 个会话

| 日期 | 类别 | 标题 | 模块 | 提交 |
|---|---|---|---|---|
| 2026-11-20 | bug | 修复部署矩阵历史元数据与实际容器缝合 | agent-gateway | `3f97378` |
| 2026-11-20 | chore | 补周报视图，校正 README 阅读成本账 | other | `bd18e87` |
| 2026-11-20 | chore | docs 体系改造：ledger 单一写入 + skills/views/find 三层消费 | other | `a381003` |
| 2026-11-20 | feat | 生产发布改为从测试验证版本直接部署 | web | `a27fe76` |
| 2026-11-20 | bug | 修复环境卡片区因 v-else 错配而空白 | web | `bb27e58` |
| 2026-11-20 | bug | 部署地址指向/镜像保留策略/版本区分/环境页镜像面板 | agent-executor, agent-gateway, web | `813904b` `342bfd9` |
| 2026-11-20 | docs | Code Wiki 刷新：镜像就绪、沙箱配置、归档链诊断 | other | `ceef158` |
| 2026-11-20 | chore | 分支清理与归档诊断工具收录 | other | `b1a8628` |
| 2026-11-20 | chore | 面板真实渲染校验脚本 + 清掉误生成的 npm 锁文件 | web | `e1583e5` |
| 2026-11-20 | chore | 收录 fd26ec8 生产按版本部署修复提交 | other | `fd26ec8` |

## 模块索引

| 模块 | 会话数 | 最近变更 | 详见 |
|---|---|---|---|
| agent-gateway | 78 | 2026-11-20 修复部署矩阵历史元数据与实际容器缝合 | [modules/agent-gateway.md](modules/agent-gateway.md) |
| other | 38 | 2026-11-20 补周报视图，校正 README 阅读成本账 | [modules/other.md](modules/other.md) |
| web | 61 | 2026-11-20 生产发布改为从测试验证版本直接部署 | [modules/web.md](modules/web.md) |
| agent-executor | 51 | 2026-11-20 部署地址指向/镜像保留策略/版本区分/环境页镜像面板 | [modules/agent-executor.md](modules/agent-executor.md) |
| agent-domain | 43 | 2026-11-20 镜像就绪状态可观测：只读接口暴露按主机维度的镜像就绪/退避 | [modules/agent-domain.md](modules/agent-domain.md) |
| agent-infra | 46 | 2026-11-20 镜像就绪状态可观测：只读接口暴露按主机维度的镜像就绪/退避 | [modules/agent-infra.md](modules/agent-infra.md) |
| agent-runtime | 27 | 2026-11-20 派发票据回写撞主键（closeTicket 误用 INSERT） | [modules/agent-runtime.md](modules/agent-runtime.md) |
| agent-governance | 13 | 2026-11-20 0.1.1 OPEN bug 批量修复（27+3 条） | [modules/agent-governance.md](modules/agent-governance.md) |
| agent-tools | 31 | 2026-11-20 0.1.1 OPEN bug 批量修复（27+3 条） | [modules/agent-tools.md](modules/agent-tools.md) |
| spec-checks | 21 | 2026-11-20 0.1.1 OPEN bug 批量修复（27+3 条） | [modules/spec-checks.md](modules/spec-checks.md) |
| pom.xml | 3 | 2026-11-17 接入 springdoc、前端契约审计与错误码收敛 | [modules/pom-xml.md](modules/pom-xml.md) |
| README.md | 1 | 2026-11-17 会话级模型选择、确认卡续跑与多版本部署 | [modules/README-md.md](modules/README-md.md) |
