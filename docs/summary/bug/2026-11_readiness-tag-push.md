---
canonical: docs/summary/2026-11/2026-11-17_1727_readiness-tag-push.md
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-17T17:39:15+08:00
extends: workspace-supervisor-stream-ask
refs:
  - bug/2026-11-17_1727_readiness-tag-push
metrics:
  sessions: 1
  casesAdded: 2
  casesTotal: 552
  passRate: "100%"
  lineCoverage: "37.8%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# 首次发布结果项展示与标签推送凭据修复

## 概述
首次发布就绪清单把「首次发布」结果项当普通检查项渲染：该项 required=false，但没有成功发布记录时仍渲染红叉，出现「顶部显示可以发布、清单却有红叉且结果列空白」的矛盾；同时它的文案「还没有在 test 跑通过一次」容易让人误以为阻塞发布。git 标签只在远端可见性上有小徽标：createTag 推送复用运行主机的本机凭据，私有仓库必然失败只剩 LOCAL/FAILED，结果区没有告警也没有重试入口。
就绪清单：结果项改用中性信息图标（不影响发布），文案改为「尚无 test 的成功发布记录」并去掉发布入口，结果标签补充说明。标签：ProjectRepoLocator 新增带 userId 的 createTag 与幂等 pushTag，DefaultRepoLocator 优先用该用户已保存的托管账号凭据推送（未配置时退回本机凭据）；流水线从 actor=ui:<userId> 取用户；新增 POST /api/v1/ci/tags/push；PipelineView 在 LOCAL/FAILED 时结果区告警并提供「重推标签」按钮，成功后刷新历史。

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `729dbaf` | bug | fix(release): 首次发布结果项中性展示，标签用平台 token 推送并支持重推 |

## 测试与质量总结
- 命令：`mvn -B -pl spec-checks -am test`
- 结果：PASSED；Tests run: 552, Failures: 0, Errors: 0, Skipped: 2
- 新增用例：2
- 行覆盖率：37.8%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/bug/2026-11-17_1727_readiness-tag-push/manifest.json`
- 上一份同域总结：workspace-supervisor-stream-ask
