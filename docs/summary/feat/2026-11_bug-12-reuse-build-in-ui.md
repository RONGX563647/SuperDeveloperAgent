---
canonical: docs/summary/2026-11/2026-11-19_1820_bug-12-reuse-build-in-ui.md
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-19T18:27:30+08:00
extends: worktree-and-gate-fixes
refs:
  - feat/2026-11-19_1820_bug-12-reuse-build-in-ui
metrics:
  sessions: 1
  casesAdded: 11
  casesTotal: 779
  passRate: "100%"
  lineCoverage: "41.0%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# BUG-12 构建历史提供复用产物部署入口

## 概述
BUG-12（为何不能复用前面产物部署）：后端 deploy_run 早已支持跳过构建（触发参数 deployImage、工具参数 build_run_id），但界面上只有「重跑」——同样代码要发到另一个环境、或只是想重发一次，都得重新走一遍构建，既多等几分钟又多产一个镜像。用户看到「前面的检查构建已经把那些跑完了、版本号也一致」，却找不到复用入口。
HistoryList 对 SUCCEEDED 的运行多一个「复用产物部署」入口（Van 图标，带 tooltip 说明跳过构建）；点击后取该次运行的镜像产物，走既有的按版本部署通道（deployImageVersion），不新增链路。可见性与取产物抽到 utils/reuseBuild.ts：canReuseBuild 只认 SUCCEEDED（未成功的运行没有可复用产物，给按钮只会点出错误），pickReusableImage 取最后一个 image（与后端「以最后产出为准」一致），没有镜像时提示「这次只跑了检查构建」——在点击之前就说清楚，而不是等后端报错。新增 11 个断言并接入前端自检。

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `2d8c0e4` | feat | feat(pipeline): 构建历史提供「复用产物部署」入口 |

## 测试与质量总结
- 命令：`mvn -B -nsu -pl !spec-checks test -Dsurefire.forkCount=4`
- 结果：PASSED；Tests run: 779, Failures: 0, Errors: 0, Skipped: 7
- 新增用例：11
- 行覆盖率：41.0%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/feat/2026-11-19_1820_bug-12-reuse-build-in-ui/manifest.json`
- 上一份同域总结：worktree-and-gate-fixes
