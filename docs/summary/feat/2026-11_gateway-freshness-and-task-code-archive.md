---
canonical: docs/summary/2026-11/2026-11-19_0122_gateway-freshness-and-task-code-archive.md
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-19T01:27:31+08:00
extends: worktree-and-gate-fixes
refs:
  - feat/2026-11-19_0122_gateway-freshness-and-task-code-archive
metrics:
  sessions: 1
  casesAdded: 4
  casesTotal: 146
  passRate: "100%"
  lineCoverage: "39.7%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# 网关构建产物新鲜度自检与归档按任务编码命名

## 概述
task-90836fe8d5 余项与 task-b19674d242 余项：网关进程长时间不重启、target/classes 被反复编译替换时，JVM 里新旧字节码混用会让请求挂住（既不返回也不报错，日志里也看不出来）；归档目录只能叫「时间_slug」，任务编码（FEAT-1/BUG-2）已经分配却进不了存档名与标题，档案与任务页对不上同一个编号。
新增 BuildFreshnessCheck：启动时抽样比对 target/classes 与 src/main/java 的修改时间，源码更新时明确告警「请重启网关，否则会出现改了不生效或请求挂住」；镜像内没有源码或没有产物时跳过，不阻断启动（实测启动日志已打出抽样 187 个类的结论）。gate.py start 新增 --task-code（BUG-12 / FEAT-3）：归档目录 slug 与标题都带编码，「存档用任务编码作标题」由此落地，未给编码时保持历史行为；self-test 增加命名契约断言。

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `2fd381e` | feat | feat(gateway): 启动期构建产物新鲜度自检；归档目录可带任务编码 |

## 测试与质量总结
- 命令：`mvn -B -nsu -pl agent-gateway,!spec-checks -amd -DskipTests -Djacoco.skip=true test-compile && mvn -B -nsu -pl agent-gateway test -Dsurefire.forkCount=4 -DexcludedGroups=slow`
- 结果：PASSED；Tests run: 146, Failures: 0, Errors: 0, Skipped: 2
- 新增用例：4
- 行覆盖率：39.7%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/feat/2026-11-19_0122_gateway-freshness-and-task-code-archive/manifest.json`
- 上一份同域总结：worktree-and-gate-fixes
