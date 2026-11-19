---
canonical: docs/summary/2026-11/2026-11-19_2001_deploy-jar-manifest-sandbox.md
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-19T20:03:07+08:00
extends: workspace-supervisor-stream-ask
refs:
  - bug/2026-11-19_2001_deploy-jar-manifest-sandbox
metrics:
  sessions: 1
  casesAdded: 7
  casesTotal: 68
  passRate: "100%"
  lineCoverage: "41.1%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# 修部署失败：拷错 jar 导致无 Main-Class，且容器内沙箱模式不可用

## 概述
run-0d8832ee 在 health 阶段失败（退出码 1 / E-401，body 为空），容器日志 no main manifest attribute, in /app/app.jar。根因一：gateway 的 repackage 用了 classifier=exec，target 下普通 jar 无 Main-Class，而 Dockerfile 写 COPY target/*.jar 抓到普通包。根因二：修好 jar 后容器仍启动失败，因为 agent.sandbox.mode 默认 docker 且 fail-closed，而平台按设计永不挂载 docker.sock，两者叠加使平台镜像在容器里必然起不来。
仓库 Dockerfile 的 COPY 锁定 *-exec.jar 并写明原因，同时显式设置 AGENT_SANDBOX_MODE=process 说明隔离边界由容器承担；DockerfileTools 新增 springBootJarClassifier 按 pom 的 classifier 精确生成拷贝路径，新增 checkRunnableJar 在生成 Dockerfile 前预检 target 下是否真有带 Main-Class 的 jar，不满足时返回点名 *-exec.jar 的可操作错误，不再把问题拖到健康检查超时。

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `b9fab0d` | docs | docs(process): 归档 BUG 清零会话 |
| `0383892` | bug | fix(deploy): 锁定可执行 jar，并让容器镜像能真正启动 |

## 测试与质量总结
- 命令：`mvn -B -nsu -pl agent-tools,!spec-checks -amd -DskipTests -Djacoco.skip=true test-compile && mvn -B -nsu -pl agent-tools test -Dsurefire.forkCount=4 -DexcludedGroups=slow`
- 结果：PASSED；Tests run: 68, Failures: 0, Errors: 0, Skipped: 0
- 新增用例：7
- 行覆盖率：41.1%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/bug/2026-11-19_2001_deploy-jar-manifest-sandbox/manifest.json`
- 上一份同域总结：workspace-supervisor-stream-ask
