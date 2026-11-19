---
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-18T14:45:23+08:00
extends: workspace-supervisor-stream-ask
refs:
  - bug/2026-11-18_1439_ci-red-lights-docker-and-tests
metrics:
  sessions: 1
  casesAdded: 1
  casesTotal: 655
  passRate: "100%"
  lineCoverage: "39.3%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# 修复流水线与测试红灯：缓存卷初始化、验收用例改走平台部署、SQLite 临时目录释放

## 概述
本机已启动 Docker，但流水线/验收用例仍红：缓存卷初始化在 Windows userns 卷上用 root 写入被拒；PipelineAcceptanceTest 在仓库 ci/actions.yml 里写 image_package / stack_deploy 并声明 runner: host，被引擎按安全规则降级或拒绝；DualStack/Intake E2E 仍断言旧的 worktree 保留语义；governance 三个 SQLite 测试在 Windows 下无法删除 @TempDir；历史 manifest 引用远端快照 sha，导致 gate validate 失败。
缓存卷初始化按 1000 → root 回退、chown 尽力而为、用 ErrorCode 代替硬编码 E-101；验收用例改用平台内置 image_package 与 deploy/rollback 段（生产实际路径）；E2E 对齐「成功即清理 worktree、产物以 artifacts 为准」；SQLite 测试在 @AfterEach 关闭缓存的独立库；按同内容本地提交修正历史 manifest sha 并重算 bug 链 hash。

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `4350159` | bug | fix(ci): 修复流水线与测试红灯 |

## 测试与质量总结
- 命令：`mvn -B -pl spec-checks -am test`
- 结果：PASSED；Tests run: 655, Failures: 0, Errors: 0, Skipped: 1
- 新增用例：1
- 行覆盖率：39.3%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/bug/2026-11-18_1439_ci-red-lights-docker-and-tests/manifest.json`
- 上一份同域总结：workspace-supervisor-stream-ask
