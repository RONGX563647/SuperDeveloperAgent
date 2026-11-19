---
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-19T19:06:50+08:00
extends: worktree-commit-hook
refs:
  - chore/2026-11-19_1904_gate-ci-speed-quality
metrics:
  sessions: 1
  casesAdded: 0
  casesTotal: 0
  passRate: "0%"
  lineCoverage: "27.1%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: WARN
---

# 门禁增量测试与CI优化

## 概述
门禁 pre-commit 按模块全量跑测试，改一个类要等整个模块（gateway 239 用例）跑完；GitHub CI 未覆盖 0.2.0-snapshot 分支，且文档/前端改动也会触发后端全量构建。
gate.py 增加类级定向测试选择：改 Foo.java 只跑 FooTest 及源码引用 Foo 的测试，资源/高风险路径/无测试/超上限一律回退模块全量，定向跑不登记模块绿色证据；CI 补齐分支触发、路径过滤 job、最小权限，失败才上传产物。

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `10c7f2c` | chore | chore: 门禁定向测试与CI路径过滤优化 |

## 测试与质量总结
- 命令：`未执行`
- 结果：SKIPPED；Tests run: 0, Failures: 0, Errors: 0, Skipped: 0
- 新增用例：0
- 行覆盖率：27.1%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**WARN**

## 风险敞口
- 未覆盖的新增文件：见失败测试
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/chore/2026-11-19_1904_gate-ci-speed-quality/manifest.json`
- 上一份同域总结：worktree-commit-hook
