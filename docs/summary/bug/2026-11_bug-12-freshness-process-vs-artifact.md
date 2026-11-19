---
canonical: docs/summary/2026-11/2026-11-19_1236_bug-12-freshness-process-vs-artifact.md
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-19T12:38:14+08:00
extends: workspace-supervisor-stream-ask
refs:
  - bug/2026-11-19_1236_bug-12-freshness-process-vs-artifact
metrics:
  sessions: 1
  casesAdded: 2
  casesTotal: 148
  passRate: "100%"
  lineCoverage: "40.5%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# BUG-12 新鲜度自检补进程与产物比对

## 概述
BuildFreshnessCheck 首版只比「源码 vs 编译产物」，在这台机器上给出的是「产物与源码一致（抽样 187 个类）」——但运行中的网关进程启动于 11-18 23:13，而 target/classes 在 11-19 12:35 又被重新编译。也就是说进程内是 12 小时前的字节码，磁盘上是新产物，源码与产物本身一致，检测因此完全静默。这正是「改了不生效 / 请求挂住不返回」的真实形态，原判据漏掉了它。
inspect 增加 processStartedAt 参数与第二条判据：产物目录里最新的 .class 时间晚于进程启动时间即判为过期，文案直接写出两个时间点（产物 X，进程启动于 Y）；启动钩子改为传入 ProcessHandle 的启动时间；保留原来的源码-产物比对作为第一条判据，两条任一命中即告警。另加 tools/task-board/freshness-probe.py 作为只读复核脚本，可用运行中进程的启动时间验证判据是否命中。用例补「进程早于产物→过期」「进程晚于产物→新鲜」两条。

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `fa138ac` | bug | fix(gateway): 新鲜度自检补「进程 vs 产物」比对 |

## 测试与质量总结
- 命令：`mvn -B -nsu -pl agent-gateway,!spec-checks -amd -DskipTests -Djacoco.skip=true test-compile && mvn -B -nsu -pl agent-gateway test -Dsurefire.forkCount=4 -DexcludedGroups=slow`
- 结果：PASSED；Tests run: 148, Failures: 0, Errors: 0, Skipped: 2
- 新增用例：2
- 行覆盖率：40.5%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/bug/2026-11-19_1236_bug-12-freshness-process-vs-artifact/manifest.json`
- 上一份同域总结：workspace-supervisor-stream-ask
