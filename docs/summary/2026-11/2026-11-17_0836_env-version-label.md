---
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-17T08:40:24+08:00
extends: null
refs:
  - bug/2026-11-17_0836_env-version-label
metrics:
  sessions: 1
  casesAdded: 3
  casesTotal: 519
  passRate: "100%"
  lineCoverage: "37.2%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# 环境矩阵显示构建版本号

## 概述
工作区环境 Tab 的部署卡片只显示 latest，看不到构建版本号，无法判断当前部署对应哪次构建。
流水线部署传给部署器的版本号从 deployVersion 改为 versionLabel：未打 tag 时回落到 <env>-v<seq>，HOST 的 version 文件与 Docker 的 agent.version 标签都写入真实版本；部署详情增加版本号一行。

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `921ffca` | bug | fix(deploy): 环境矩阵显示构建版本号而不是 latest |
| `4a64f83` | chore | test(deploy): 覆盖部署版本号的回落规则 |

## 测试与质量总结
- 命令：`mvn -B -pl spec-checks -am test`
- 结果：PASSED；Tests run: 519, Failures: 0, Errors: 0, Skipped: 2
- 新增用例：3
- 行覆盖率：37.2%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 历史部署的 `version` 文件仍可能是 `latest`，代码不会追溯改写；本次已把当前 test 环境的标签修正为 `test-v100`，下次部署起自动写入 `test-vN`。
- 未打 tag 的默认槽标签随构建序号变化，环境矩阵只保留最新一次；需要长期并存的版本仍应打 tag。
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 老部署行如需展示真实版本，可在环境矩阵里按 release sha 回查构建历史补齐（本次未做，避免误标）。

## 链接
- 归档：`docs/process/bug/2026-11-17_0836_env-version-label/manifest.json`
- 上一份同域总结：null
