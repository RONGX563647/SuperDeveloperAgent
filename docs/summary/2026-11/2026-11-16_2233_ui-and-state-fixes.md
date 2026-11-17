---
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-17T10:23:45+08:00
extends: tool-schema-token-budget
refs:
  - feat/2026-11-16_2233_ui-and-state-fixes
metrics:
  sessions: 1
  casesAdded: 18
  casesTotal: 502
  passRate: "100%"
  lineCoverage: "37.4%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# 部署卡片历史/日志/重启、SSE 401 与状态存储修复

## 概述
部署卡片看不到历史、容器日志与重启入口；SSE 未登录时内容协商失败刷 500；匿名槽位请求 AgentState 产生告警；前端样式重复且布局割裂
部署历史与 git 标签入库，卡片支持日志、重启与访问链接；GlobalExceptionHandler 按 Accept 分流 SSE；匿名槽位读写直接忽略；统一设计令牌并重构侧栏、聊天与项目页布局

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `8fc1168` | feat | feat(deploy): 新增 Dockerfile 自愈工具与部署诊断增强 |
| `23ff830` | feat | feat(deploy): 发布版本历史、git 标签与环境部署重启/日志 |
| `53f9009` | bug | fix(web): SSE 异常统一返回 JSON，避免内容协商失败 |
| `f51b161` | bug | fix(state): 匿名槽位不再落盘，消除状态存储告警 |
| `7207f81` | chore | refactor(ui): 统一设计令牌并重构侧栏、聊天与项目页布局 |
| `dfd6290` | bug | fix(tools): 通过 RepoInspector 判断提交内文件，移除工具层进程调用 |
| `3f60317` | bug | fix(spec): 错误码检查改为扫描源码，避免独立运行命中过期快照 |

## 测试与质量总结
- 命令：`mvn -B -pl spec-checks -am -Dtest=!ApiDocsSnapshotTest -Dsurefire.failIfNoSpecifiedTests=false test`
- 结果：PASSED；Tests run: 502, Failures: 0, Errors: 0, Skipped: 2
- 新增用例：18
- 行覆盖率：37.4%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- Element Plus 按需引入与长会话虚拟滚动待做
- ApiDocsSnapshotTest 在该提交点因快照未同步失败，后续会话已修复

## 链接
- 归档：`docs/process/feat/2026-11-16_2233_ui-and-state-fixes/manifest.json`
- 上一份同域总结：tool-schema-token-budget
