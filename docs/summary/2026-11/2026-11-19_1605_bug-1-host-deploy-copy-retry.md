---
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-19T16:07:16+08:00
extends: workspace-supervisor-stream-ask
refs:
  - bug/2026-11-19_1605_bug-1-host-deploy-copy-retry
metrics:
  sessions: 1
  casesAdded: 3
  casesTotal: 209
  passRate: "100%"
  lineCoverage: "40.4%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# BUG-1 HOST 部署产物复制重试与占用提示

## 概述
BUG-1（流水线部署问题：遇到不同环境经常中断导致运行不成功）。扫描 34 份运行历史，17 份失败里 E-501 占 9 次，是最主要的失败码；逐条看原文后确认其中一条是真实缺陷而非配置问题：run-58f89f75 报「复制产物失败：...releases/ec462cb/app.jar 另一个程序正在使用此文件，进程无法访问」。这类失败重跑一次就成功，属于把「短暂占用」当成「永久失败」。根因在 HostDeployer：它先复制 jar 到 releases/<sha>/app.jar，之后才停止旧进程；而 Windows 对正在运行的 jar 持有写锁，加上目标文件可能被上一实例的句柄短暂占用，直接 Files.copy 必然失败并中断整次部署。
新增 copyReplacing：先复制到同目录 .tmp 再原子 move 到目标（避免中途失败在目标位置留下半个 jar，让下次启动拿到坏产物），对占用情况重试 6 次、间隔 500ms（约 3 秒，覆盖旧实例退出与杀毒扫描）；失败时清掉 .tmp 并抛出保留底层原因的异常。部署失败文案改为可操作：提示「若提示文件被占用，说明同版本实例未停止，先停止它或改用不同的 version 发布」。另留一个包级 copyReplacingForTest 供测试调用，生产代码无调用点。新增 HostDeployerCopyTest 3 例：正常复制内容一致且不留 .tmp；用 FileChannel 独占锁模拟真实「文件正在使用」并验证重试后成功；复制失败时错误信息保留可定位的文件名且不留残渣。

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `fce29f9` | bug | fix(deploy): HOST 产物复制对短暂占用重试，失败时给出可操作原因 |

## 测试与质量总结
- 命令：`mvn -B -nsu -pl agent-executor,!spec-checks -amd -DskipTests -Djacoco.skip=true test-compile && mvn -B -nsu -pl agent-executor test -Dsurefire.forkCount=4 -DexcludedGroups=slow`
- 结果：PASSED；Tests run: 209, Failures: 0, Errors: 0, Skipped: 0
- 新增用例：3
- 行覆盖率：40.4%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/bug/2026-11-19_1605_bug-1-host-deploy-copy-retry/manifest.json`
- 上一份同域总结：workspace-supervisor-stream-ask
