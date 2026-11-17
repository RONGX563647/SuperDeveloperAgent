---
canonical: docs/summary/2026-11/2026-11-17_1914_version-branch-base.md
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-17T19:17:53+08:00
extends: worktree-commit-hook
refs:
  - chore/2026-11-17_1914_version-branch-base
metrics:
  sessions: 1
  casesAdded: 0
  casesTotal: 572
  passRate: "100%"
  lineCoverage: "38.4%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# 新版本线默认从上一补丁标签切出

## 概述
创建新版本线分支时默认从当前 HEAD 切出，会和「新版本线从上一条版本线最后一个补丁发布开始切」的规则不一致；操作者必须手工记住上一条线发布了哪个补丁。
项目分支创建接口在未显式传 from 时自动取上一条版本线的最后一个补丁标签（0.2.0-snapshot ← 0.1 系列最大补丁；0.1.0-snapshot ← main），找不到标签时退回 main/HEAD，并在返回体里给出实际起点 from。

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `155b7fd` | feat | feat(release): 新版本线默认从上一版本最后一个补丁标签切出 |

## 测试与质量总结
- 命令：`mvn -B -pl spec-checks -am test`
- 结果：PASSED；Tests run: 572, Failures: 0, Errors: 0, Skipped: 2
- 新增用例：0
- 行覆盖率：38.4%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/chore/2026-11-17_1914_version-branch-base/manifest.json`
- 上一份同域总结：worktree-commit-hook
