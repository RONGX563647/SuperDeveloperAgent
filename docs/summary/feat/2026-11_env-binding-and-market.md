---
canonical: docs/summary/2026-11/2026-11-16_1456_env-binding-and-market.md
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-17T10:23:26+08:00
extends: tool-schema-token-budget
refs:
  - feat/2026-11-16_1456_env-binding-and-market
metrics:
  sessions: 1
  casesAdded: 14
  casesTotal: 463
  passRate: "100%"
  lineCoverage: "37.4%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# 环境绑定对话内确认、会话级联删除与能力市场外部目录

## 概述
环境绑定要跳菜单确认；删除会话只删主表导致刷新后复现；能力市场目录写死；流水线环境类故障需要人工修复
环境绑定改为对话内确认卡；删除会话级联清理运行账本、任务与 Agent 记忆；能力市场改读外部目录；环境故障按最新配置自动续跑（含 Docker 守护进程自愈）；前端构建改用 corepack 兼容非 root 容器

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `240661f` | bug | fix(web): 切换会话不再中断后台运行 |
| `8accc94` | feat | feat(settings): 能力市场改为读取外部目录 |
| `e91a64b` | feat | feat(workspace): 删除会话时级联清理服务端数据 |
| `d27edd1` | feat | feat(chat): 环境绑定改为在对话内确认 |
| `7db1ccc` | bug | fix(infra): 前端构建改用 corepack 子命令，兼容非 root 容器 |
| `3c736c6` | bug | fix(pipeline): 环境类故障自动修复，部署目标以定义为准 |
| `9033a9b` | chore | style(web): 设置页内容区撑满可视高度 |
| `5e7e945` | chore | chore(repo): 清理未被引用的上传图片 |
| `15097cc` | docs | docs(agent): 工具说明同步对话内环境绑定确认 |

## 测试与质量总结
- 命令：`mvn -B -pl spec-checks -am test`
- 结果：PASSED；Tests run: 463, Failures: 0, Errors: 0, Skipped: 2
- 新增用例：14
- 行覆盖率：37.4%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/feat/2026-11-16_1456_env-binding-and-market/manifest.json`
- 上一份同域总结：tool-schema-token-budget
