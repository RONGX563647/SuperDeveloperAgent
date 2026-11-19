---
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-18T01:01:05+08:00
extends: workspace-supervisor-stream-ask
refs:
  - bug/2026-11-18_0057_host-deploy-orphan-reclaim
metrics:
  sessions: 1
  casesAdded: 1
  casesTotal: 603
  passRate: "100%"
  lineCoverage: "38.9%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# host 部署回收同槽遗留进程

## 概述
HOST 部署时上一次的进程可能残留（pid 文件丢失或被覆盖），旧实例继续占用端口，新进程绑定失败而健康检查打到旧进程
槽内追加登记 pid 与启动时刻（app.pids），部署前按登记回收遗留进程；启动时刻不符即视为 pid 复用不误杀；进度按槽隔离不影响其它版本

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `d34a378` | bug | fix(deploy): HOST 部署回收同槽遗留进程，避免旧实例占端口 |

## 测试与质量总结
- 命令：`mvn -B -pl spec-checks -am test`
- 结果：PASSED；Tests run: 603, Failures: 0, Errors: 0, Skipped: 2
- 新增用例：1
- 行覆盖率：38.9%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/bug/2026-11-18_0057_host-deploy-orphan-reclaim/manifest.json`
- 上一份同域总结：workspace-supervisor-stream-ask
