<!-- 由 tools/docs/build.py 生成，勿手改；文档地图见 ../README.md -->
# 近期变更索引

留痕数据：`docs/ledger/*.jsonl`（166 个会话，截至 2026-11-20）。查历史用 `python tools/docs/find.py "<关键词>"`，不要整树通读。

## 最近 10 个会话

| 日期 | 类别 | 标题 | 模块 | 提交 |
|---|---|---|---|---|
| 2026-11-20 | chore | docs 体系改造：ledger 单一写入 + skills/views/find 三层消费 | other | `a381003` |
| 2026-11-20 | docs | Code Wiki 刷新：镜像就绪、沙箱配置、归档链诊断 | other | `ceef158` |
| 2026-11-20 | chore | 分支清理与归档诊断工具收录 | other | `b1a8628` |
| 2026-11-20 | chore | 面板真实渲染校验脚本 + 清掉误生成的 npm 锁文件 | web | `e1583e5` |
| 2026-11-20 | chore | 收录 fd26ec8 生产按版本部署修复提交 | other | `fd26ec8` |
| 2026-11-20 | feat | 设置页新增工具链镜像面板（就绪/退避可视） | web | `da6cfff` |
| 2026-11-20 | bug | 固化脚本缺 deploy 阶段导致健康检查必败 | agent-executor | `b519605` |
| 2026-11-20 | bug | forceTerminate 用例竞态：cancel 落在任务启动前，等 25s 必失败 | agent-executor | `d7b3cf7` `6142e67` |
| 2026-11-20 | bug | 生产按版本部署：选项改为测试环境成功运行的版本 | agent-executor, agent-gateway, web | `fd26ec8` |
| 2026-11-20 | chore | 补齐接口契约：新端点加 @Operation(summary)，spec-checks 恢复绿 | agent-gateway | `e72ff5d` |

## 模块索引

| 模块 | 会话数 | 最近变更 | 详见 |
|---|---|---|---|
| other | 37 | 2026-11-20 docs 体系改造：ledger 单一写入 + skills/views/find 三层消费 | [modules/other.md](modules/other.md) |
| web | 58 | 2026-11-20 面板真实渲染校验脚本 + 清掉误生成的 npm 锁文件 | [modules/web.md](modules/web.md) |
| agent-executor | 50 | 2026-11-20 固化脚本缺 deploy 阶段导致健康检查必败 | [modules/agent-executor.md](modules/agent-executor.md) |
| agent-gateway | 76 | 2026-11-20 生产按版本部署：选项改为测试环境成功运行的版本 | [modules/agent-gateway.md](modules/agent-gateway.md) |
| agent-domain | 43 | 2026-11-20 镜像就绪状态可观测：只读接口暴露按主机维度的镜像就绪/退避 | [modules/agent-domain.md](modules/agent-domain.md) |
| agent-infra | 46 | 2026-11-20 镜像就绪状态可观测：只读接口暴露按主机维度的镜像就绪/退避 | [modules/agent-infra.md](modules/agent-infra.md) |
| agent-runtime | 27 | 2026-11-20 派发票据回写撞主键（closeTicket 误用 INSERT） | [modules/agent-runtime.md](modules/agent-runtime.md) |
| agent-governance | 13 | 2026-11-20 0.1.1 OPEN bug 批量修复（27+3 条） | [modules/agent-governance.md](modules/agent-governance.md) |
| agent-tools | 31 | 2026-11-20 0.1.1 OPEN bug 批量修复（27+3 条） | [modules/agent-tools.md](modules/agent-tools.md) |
| spec-checks | 21 | 2026-11-20 0.1.1 OPEN bug 批量修复（27+3 条） | [modules/spec-checks.md](modules/spec-checks.md) |
| pom.xml | 3 | 2026-11-17 接入 springdoc、前端契约审计与错误码收敛 | [modules/pom-xml.md](modules/pom-xml.md) |
| README.md | 1 | 2026-11-17 会话级模型选择、确认卡续跑与多版本部署 | [modules/README-md.md](modules/README-md.md) |
