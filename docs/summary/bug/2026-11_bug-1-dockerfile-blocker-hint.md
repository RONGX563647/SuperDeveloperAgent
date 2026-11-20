---
canonical: docs/summary/2026-11/2026-11-19_1850_bug-1-dockerfile-blocker-hint.md
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-19T18:51:03+08:00
extends: workspace-supervisor-stream-ask
refs:
  - bug/2026-11-19_1850_bug-1-dockerfile-blocker-hint
metrics:
  sessions: 1
  casesAdded: 3
  casesTotal: 200
  passRate: "100%"
  lineCoverage: "41.0%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# BUG-1 缺 Dockerfile 的预检提示指向自动生成工具

## 概述
BUG-1（流水线部署问题：遇到不同环境经常中断导致运行不成功）的最后一半。前一半「复制产物被文件占用」已修（fce29f9/3f22301）。剩下的一半是 DOCKER 目标下仓库没有 Dockerfile：预检确实把这种情况挡住了，但文案只写「先生成 Dockerfile，用新提交再发布」——用户不知道平台已经能按仓库结构自动生成，只能手写一个；手写的 Dockerfile 又经常和镜像阶段、目录结构对不上，于是又失败一次。历史 17 份失败运行里 E-501 出现 9 次，这条占其中相当一部分。
抽出 BuiltInPipelineEngine.missingDockerfileBlocker，两条预检路径（有镜像阶段缺文件、DOCKER 目标无 Dockerfile）共用同一份文案：明确给出工具名 ensure_dockerfile、说明平台会按仓库结构生成并补齐镜像阶段、说明产出变更单且「确认后自动用新提交重跑」、并写明「不需要手写 Dockerfile」。判定逻辑不动（missingImageDockerfile 仍按命令里的 -f 路径判断，避免误判自定义配方）。新增 DockerfileHintTextTest 3 例：必须出现工具名与「不需要手写」、必须带具体缺失路径与错误码、且不得引导用户去 shell 手写或自己跑 docker build。

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `96baa9b` | bug | fix(deploy): 缺 Dockerfile 的预检提示指向 ensure_dockerfile |

## 测试与质量总结
- 命令：`mvn -B -nsu -pl agent-executor,!spec-checks -amd -DskipTests -Djacoco.skip=true test-compile && mvn -B -nsu -pl agent-executor test -Dsurefire.forkCount=4 -DexcludedGroups=slow`
- 结果：PASSED；Tests run: 200, Failures: 0, Errors: 0, Skipped: 0
- 新增用例：3
- 行覆盖率：41.0%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/bug/2026-11-19_1850_bug-1-dockerfile-blocker-hint/manifest.json`
- 上一份同域总结：workspace-supervisor-stream-ask
