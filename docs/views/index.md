<!-- 由 tools/docs/build.py 生成，勿手改；文档地图见 ../README.md -->
# 近期变更索引

留痕数据：`docs/ledger/*.jsonl`（177 个会话，截至 2026-11-20）。查历史用 `python tools/docs/find.py "<关键词>"`，不要整树通读。

## 最近 10 个会话

| 日期 | 类别 | 标题 | 模块 | 提交 |
|---|---|---|---|---|
| 2026-11-20 | chore | 补周报视图，校正 README 阅读成本账 | other | `bd18e87` |
| 2026-11-20 | chore | docs 体系改造：ledger 单一写入 + skills/views/find 三层消费 | other | `a381003` |
| 2026-11-20 | feat | 生产发布改为从测试验证版本直接部署 | web | `a27fe76` |
| 2026-11-20 | bug | 修复环境卡片区因 v-else 错配而空白 | web | `bb27e58` |
| 2026-11-20 | bug | 部署地址指向/镜像保留策略/版本区分/环境页镜像面板 | agent-executor, agent-gateway, web | `813904b` `342bfd9` |
| 2026-11-20 | docs | Code Wiki 刷新：镜像就绪、沙箱配置、归档链诊断 | other | `ceef158` |
| 2026-11-20 | chore | 同步 0.1 分支到 0.2 分支（合并 + 重封归档链） | agent-domain, agent-executor, agent-gateway, agent-governance, agent-infra, agent-runtime, agent-tools, spec-checks, web | `dbeb728` `fe0b85f` `2a4591d` `cfaf487` `ac40c70` `6a8d0b9` `a054113` `9b96b52` `d6d5f25` `60accb8` `d2ce0ba` `0ef4309` `a0d886b` `f231e12` `ad02dad` `a48c78e` `6a0c837` `48a8c15` `501439d` `3e39c2c` `a3e360a` `b33cb9e` `239ee27` `ce82776` `c21e04a` `e2e69b6` `0a04b9c` `d9c2541` `25c7716` `f0a5776` `1b998b8` `1f84d49` `ab86d4e` `cc2256c` `c16ed3d` `d3cb637` `83c702f` `3f42162` `e92e7a0` `bea5a2a` `6acdb24` `b0124a9` `d103bd6` `fd01545` `d70eb9f` `16c6f4c` `0054f5f` `fd2636d` `e788458` `2674270` `ce30f40` `a368565` `3981a7c` `591cffc` `b6a0ca8` `7573c5c` `fafb765` `03f5aee` `f380411` `3bccf5c` `cc48ad1` `4c91eed` `2b50269` `976815b` `6b132e9` `7e41654` `d1e15aa` `e72ff5d` `70a395c` `fd26ec8` `c079f5d` `e57b713` `d7b3cf7` `6142e67` `631b391` `d39c02f` `da6cfff` `d1bc7cc` `b519605` `da667d4` `4656464` `c1276b1` `fc8eab2` `0e1f446` `97df414` `e1583e5` `9abc943` `616e7d1` `b1a8628` `e9a39d3` |
| 2026-11-20 | chore | 分支清理与归档诊断工具收录 | other | `b1a8628` |
| 2026-11-20 | chore | 面板真实渲染校验脚本 + 清掉误生成的 npm 锁文件 | web | `e1583e5` |
| 2026-11-20 | chore | 收录 fd26ec8 生产按版本部署修复提交 | other | `fd26ec8` |

## 模块索引

| 模块 | 会话数 | 最近变更 | 详见 |
|---|---|---|---|
| other | 39 | 2026-11-20 补周报视图，校正 README 阅读成本账 | [modules/other.md](modules/other.md) |
| web | 64 | 2026-11-20 生产发布改为从测试验证版本直接部署 | [modules/web.md](modules/web.md) |
| agent-executor | 53 | 2026-11-20 部署地址指向/镜像保留策略/版本区分/环境页镜像面板 | [modules/agent-executor.md](modules/agent-executor.md) |
| agent-gateway | 83 | 2026-11-20 部署地址指向/镜像保留策略/版本区分/环境页镜像面板 | [modules/agent-gateway.md](modules/agent-gateway.md) |
| agent-domain | 46 | 2026-11-20 同步 0.1 分支到 0.2 分支（合并 + 重封归档链） | [modules/agent-domain.md](modules/agent-domain.md) |
| agent-governance | 14 | 2026-11-20 同步 0.1 分支到 0.2 分支（合并 + 重封归档链） | [modules/agent-governance.md](modules/agent-governance.md) |
| agent-infra | 49 | 2026-11-20 同步 0.1 分支到 0.2 分支（合并 + 重封归档链） | [modules/agent-infra.md](modules/agent-infra.md) |
| agent-runtime | 29 | 2026-11-20 同步 0.1 分支到 0.2 分支（合并 + 重封归档链） | [modules/agent-runtime.md](modules/agent-runtime.md) |
| agent-tools | 33 | 2026-11-20 同步 0.1 分支到 0.2 分支（合并 + 重封归档链） | [modules/agent-tools.md](modules/agent-tools.md) |
| spec-checks | 23 | 2026-11-20 同步 0.1 分支到 0.2 分支（合并 + 重封归档链） | [modules/spec-checks.md](modules/spec-checks.md) |
| pom.xml | 3 | 2026-11-17 接入 springdoc、前端契约审计与错误码收敛 | [modules/pom-xml.md](modules/pom-xml.md) |
| README.md | 1 | 2026-11-17 会话级模型选择、确认卡续跑与多版本部署 | [modules/README-md.md](modules/README-md.md) |
