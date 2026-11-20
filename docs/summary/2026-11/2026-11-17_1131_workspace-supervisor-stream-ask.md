---
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-17T12:15:48+08:00
extends: pipeline-tag-and-history
refs:
  - bug/2026-11-17_1131_workspace-supervisor-stream-ask
metrics:
  sessions: 1
  casesAdded: 2
  casesTotal: 546
  passRate: "100%"
  lineCoverage: "37.5%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# 工作区助手流不结束与 ask 选项卡片

## 概述
工作区助手每轮对话结束必须手动点停止才能发下一条；对话缺少 ask 选项卡片供用户选择
运行终态（run.completed/failed/cancelled）到达即收尾并断开读流，工作区 SSE 在 AGENT_END 主动关流释放配额；新增 ask_user 工具 + AskUserMiddleware 下发 ask.requested，前端 AskUserCard 渲染可点选卡片并作为下一条用户消息发回；同时补齐宿主 Docker 阶段的可信配方规则与 DeployStageTest 夹具

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `dbadfba` | bug | fix(workspace): 运行终态即收尾，避免工作区对话必须手动停止 |
| `4899aef` | bug | fix(pipeline): 镜像打包用内置配方上宿主，夹具改用宿主执行器替身 |
| `965c8c1` | feat | feat(chat): ask_user 工具下发选项卡片，对话里直接点选用答案 |
| `4b06e4f` | chore | chore(gate): write-tree 遇到 index.lock 时短重试，避免并行 git 操作误拦截 |
| `dd9d791` | chore | test(chat): 覆盖 ask_user 的 allowText 缺省行为 |

## 测试与质量总结
- 命令：`mvn -B -pl spec-checks -am test`
- 结果：PASSED；Tests run: 546, Failures: 0, Errors: 0, Skipped: 2
- 新增用例：2
- 行覆盖率：37.5%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/bug/2026-11-17_1131_workspace-supervisor-stream-ask/manifest.json`
- 上一份同域总结：pipeline-tag-and-history
