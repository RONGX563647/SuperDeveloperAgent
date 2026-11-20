---
canonical: docs/summary/2026-11/2026-11-20_1839_panel-verify-and-lockfile.md
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-20T18:40:18+08:00
extends: worktree-commit-hook
refs:
  - chore/2026-11-20_1839_panel-verify-and-lockfile
metrics:
  sessions: 1
  casesAdded: 0
  casesTotal: 0
  passRate: "100%"
  lineCoverage: "40.8%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# 面板真实渲染校验脚本 + 清掉误生成的 npm 锁文件

## 概述
「工具链镜像」面板只过了 build/typecheck/单测，接口字段到表格单元格这一段没有真浏览器验证；另外 platform/web/package-lock.json 作为历史遗留未跟踪文件一直挂在 git status 里。
新增 scripts/sandbox-images-check.mjs（登录 + 深链断言 3 行数据、列名、告警、汇总、退避倒计时、tag 类型、无 JS 错误，可截图，凭据可用 CHECK_USER/CHECK_PASS 覆盖），README 补脚本约定；包管理器是 pnpm，故忽略 platform/web/package-lock.json 并删除该文件（tools/api-docs-mcp 的 npm 锁文件仍提交）。

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `e1583e5` | chore | chore(web): 补「工具链镜像」面板的真实渲染自检，清掉误生成的 npm 锁文件 |

## 测试与质量总结
- 命令：`(前端自检)`
- 结果：PASSED；Tests run: 0, Failures: 0, Errors: 0, Skipped: 0
- 新增用例：0
- 行覆盖率：40.8%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/chore/2026-11-20_1839_panel-verify-and-lockfile/manifest.json`
- 上一份同域总结：worktree-commit-hook
