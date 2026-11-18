---
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-18T23:38:17+08:00
extends: worktree-and-gate-fixes
refs:
  - feat/2026-11-18_2330_patch-guard-src-main
metrics:
  sessions: 1
  casesAdded: 8
  casesTotal: 424
  passRate: "100%"
  lineCoverage: "40.1%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# 补丁守卫放行 src-main 并返回结构化拒绝

## 概述
task-9779e75d91（P0 真阻塞）：propose_patch 的路径守卫白名单过窄，agent 连自己仓库的 src/main 都写不进，所有「让 agent 自己改代码」的 FEAT 与后端 bug 修复全部空转；拒绝时只给笼统失败，模型会反复重试同一路径。
把业务源码 src/main/** 与测试代码 src/test/** 纳入允许清单；新增拒绝清单（优先级高于允许）覆盖 .git/、target/、build/、dist/、node_modules/ 与凭证文件（.env / *.pem / *.key / id_rsa* / .npmrc 等）；拒绝时抛 PatchPathDeniedException，输出结构化 PATH_DENIED + 被拒路径 + 命中规则 + 允许/禁止清单 + next；apply 阶段二次校验，绕过 stage 的补丁同样写不进危险路径；提示词与失败恢复建议同步成新的允许范围。

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `4e8ddfb` | feat | feat(patch): 补丁守卫放行 src/main 并返回结构化 PATH_DENIED |

## 测试与质量总结
- 命令：`mvn -B -nsu -pl agent-domain,agent-gateway,agent-infra,agent-runtime,agent-tools,!spec-checks -amd -DskipTests -Djacoco.skip=true test-compile && mvn -B -nsu -pl agent-domain,agent-gateway,agent-infra,agent-runtime,agent-tools test -Dsurefire.forkCount=4 -DexcludedGroups=slow`
- 结果：PASSED；Tests run: 424, Failures: 0, Errors: 0, Skipped: 3
- 新增用例：8
- 行覆盖率：40.1%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/feat/2026-11-18_2330_patch-guard-src-main/manifest.json`
- 上一份同域总结：worktree-and-gate-fixes
