---
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-19T00:33:02+08:00
extends: worktree-and-gate-fixes
refs:
  - feat/2026-11-19_0024_sandbox-structured-denied
metrics:
  sessions: 1
  casesAdded: 7
  casesTotal: 246
  passRate: "100%"
  lineCoverage: "40.4%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# 沙箱违规返回结构化 SANDBOX_DENIED

## 概述
task-4730f75e69（流水线沙箱脆弱且不安全）：隔离参数（gVisor、cap-drop ALL、no-new-privileges、只读根 + tmpfs、资源上限、缓存卷用可信最小镜像初始化）已在前几次会话落地，但违规时的表达是裸异常/裸退出码——网络被策略关断、追加宿主挂载、要求特权、要求的运行时不可用这四类拒绝都被压成一句 stderr 或笼统的 EXECUTABLE_NOT_FOUND，页面与自愈无法区分「该改配置」还是「该改代码」。
新增 SandboxDeniedException（固定 E-707 ENV_POLICY_DENIED），带 NETWORK/MOUNT/PRIVILEGE/IMAGE/RUNTIME/RESOURCE 六类规则、命中详情、当前隔离快照与可执行建议；DockerSandbox 增加 isolationSnapshot/logIsolation 与 requireNetworkFor/requireNoExtraMounts/requireUnprivileged/requireRuntimeAvailable 四个校验（运行时不可用时明确拒绝，绝不静默回落 runc）；DockerActionRunner 在准备阶段先落隔离快照，并把拒绝转成结构化失败（exit 126 + E-707）而不是笼统错误码。

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `6fa64cd` | feat | feat(sandbox): 策略违规返回结构化 SANDBOX_DENIED 与隔离快照 |

## 测试与质量总结
- 命令：`mvn -B -nsu -pl agent-domain,agent-executor,!spec-checks -amd -DskipTests -Djacoco.skip=true test-compile && mvn -B -nsu -pl agent-domain,agent-executor test -Dsurefire.forkCount=4 -DexcludedGroups=slow`
- 结果：PASSED；Tests run: 246, Failures: 0, Errors: 0, Skipped: 0
- 新增用例：7
- 行覆盖率：40.4%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/feat/2026-11-19_0024_sandbox-structured-denied/manifest.json`
- 上一份同域总结：worktree-and-gate-fixes
