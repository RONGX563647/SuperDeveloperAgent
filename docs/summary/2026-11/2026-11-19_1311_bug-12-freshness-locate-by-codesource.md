---
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-19T13:13:02+08:00
extends: workspace-supervisor-stream-ask
refs:
  - bug/2026-11-19_1311_bug-12-freshness-locate-by-codesource
metrics:
  sessions: 1
  casesAdded: 1
  casesTotal: 149
  passRate: "100%"
  lineCoverage: "40.6%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# BUG-12 新鲜度自检按 CodeSource 定位产物目录

## 概述
重启网关后发现新鲜度自检根本没执行：日志里既没有 stale 告警也没有 fresh 结论。原因是自检按相对路径找 target/classes，而进程的工作目录是仓库根，多模块布局下这个路径不存在，代码走了「没有编译产物目录」的跳过分支——一个会静默失效的自检等于没有自检，而这恰恰是它要防的那类问题。
改为从本类自身的 CodeSource 定位实际加载目录（即 agent-gateway/target/classes），源码目录由产物目录反推（target/classes 的父父目录 + src/main/java），完全不依赖进程工作目录；以 jar 方式运行时 CodeSource 不是目录，明确跳过并说明「那类部署形态没有源码可比」。方法拆成 inspectClasses(产物目录, 进程启动时间) 与 sourceRootOf(产物目录)，避免 Path/Instant 重载歧义。另加 tools/task-board/runtime-probe.py：未登录探活本轮新增的三个路由，404 就说明网关还在跑旧字节码——本次重启正是靠它确认新代码已生效。

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `ba7b231` | bug | fix(gateway): 新鲜度自检按 CodeSource 定位产物目录 |

## 测试与质量总结
- 命令：`mvn -B -nsu -pl agent-gateway,!spec-checks -amd -DskipTests -Djacoco.skip=true test-compile && mvn -B -nsu -pl agent-gateway test -Dsurefire.forkCount=4 -DexcludedGroups=slow`
- 结果：PASSED；Tests run: 149, Failures: 0, Errors: 0, Skipped: 2
- 新增用例：1
- 行覆盖率：40.6%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/bug/2026-11-19_1311_bug-12-freshness-locate-by-codesource/manifest.json`
- 上一份同域总结：workspace-supervisor-stream-ask
