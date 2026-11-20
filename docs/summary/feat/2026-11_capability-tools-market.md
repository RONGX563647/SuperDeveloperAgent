---
canonical: docs/summary/2026-11/2026-11-20_2123_capability-tools-market.md
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-20T22:06:01+08:00
extends: worktree-and-gate-fixes
refs:
  - feat/2026-11-20_2123_capability-tools-market
metrics:
  sessions: 1
  casesAdded: 8
  casesTotal: 68
  passRate: "100%"
  lineCoverage: "44.1%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# 会话能力页增加内置工具展示并改造界面与市场多源接入

## 概述
会话能力页缺少内置工具展示、界面信息层级差、MCP/Skill 市场仅单一仓库来源
能力页新增内置工具区（复用 AgentToolCatalog 投影）并重做三卡概览界面；市场改为多源接入（多 Registry/多 GitHub 仓库 + 来源筛选 + 接入说明）

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `a381003` | chore | chore(archive): docs 体系改造——ledger 单一写入，skills/views/find 三层消费 |
| `df34598` | docs | docs(archive): 归档 chore/2026-11-20 docs 体系改造会话（ledger 首行 + views 刷新） |
| `59f07ad` | chore | chore(archive): 吸收并行会话的 3 份旧格式归档入 ledger（迁移脚本改幂等合并） |
| `bd18e87` | chore | chore(docs): 补周报视图，校正 README 的阅读成本账 |
| `7296fc8` | docs | docs(archive): 归档 chore/2026-11-20 周报视图与预算账校正 会话 |
| `b9add58` | docs | docs(platform): 沙箱分层口径定稿 + 宿主工具注册守卫 |
| `415b525` | docs | docs(process): 提交中断会话遗留的合法归档迁移文件（168 份校验通过） |
| `43eb19c` | feat | feat(capability): 能力页加内置工具展示并重做界面；MCP/Skill 市场支持多源接入 |
| `8139279` | bug | fix(process): 门禁并发健壮性——僵尸锁 PID 自愈 + index.lock 退避 + 证据指纹抗并行噪音 |
| `35611ee` | bug | fix(project): 修复仓库地址/工作树阻断并移除登记地址幽灵字段 |

## 测试与质量总结
- 命令：`独立验证：mvn -B -nsu -pl agent-gateway -am -Dtest=RemoteCapabilityMarketTest,CapabilityStateTest -Dsurefire.failIfNoSpecifiedTests=false test (10例) + mvn -B -nsu -pl spec-checks test (35例) + node tools/run-tests.mjs (13文件/23例) + npm run typecheck`
- 结果：PASSED；Tests run: 68, Failures: 0, Errors: 0, Skipped: 0
- 新增用例：8
- 行覆盖率：44.1%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/feat/2026-11-20_2123_capability-tools-market/manifest.json`
- 上一份同域总结：worktree-and-gate-fixes
