---
canonical: docs/summary/2026-11/2026-11-18_0850_merge-remote-snapshot.md
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-18T08:52:58+08:00
extends: worktree-commit-hook
refs:
  - chore/2026-11-18_0850_merge-remote-snapshot
metrics:
  sessions: 1
  casesAdded: 32
  casesTotal: 602
  passRate: "100%"
  lineCoverage: "38.0%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# 合并远端快照并修复 HostDeployerTest 跨平台依赖

## 概述
本地 0.1.0-snapshot 与远端分叉（本地 9 个持久化提交，远端 26 个沙箱/变更卡提交），直接 push 会被拒绝；且两处改动在 application.yml 与 ChangeRegistry 重叠。
merge origin/0.1.0-snapshot：application.yml 两侧改动合并；ChangeRegistry 以 JPA 实现为底并补齐远端新增的状态流转，change_plans 实体加 status 列；HostDeployerTest 的模拟进程按操作系统选择 powershell 或 sleep。

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `898c6e0` | bug | fix(change): 留痕单不弹确认卡、失败回执回灌并支持查询变更状态 |
| `412043c` | docs | docs: 归档变更卡状态与回执修复会话 |
| `c274cc8` | chore | chore(gate): 测试门禁跨 worktree 加锁并按工作区指纹复用绿色证据 |
| `c6a2e4a` | bug | fix(pipeline): verify 续跑不再退化成发布，沙箱初始化可重试 |
| `8ef1466` | bug | fix(autofix): 变量齐全的绑定自动确认，工具失败不再记成功 |
| `d8ce889` | docs | docs: 归档流水线 verify 续跑与自愈可靠性修复会话 |
| `769dd14` | bug | fix(pipeline): 前端构建注入 npm 镜像与 pnpm store，放宽冷安装预算 |
| `4b1bc6c` | docs | docs: 补录 amend 后的变更卡提交归档（898c6e0） |
| `0f65030` | docs | docs: 归档前端构建超时修复会话 |
| `d34a378` | bug | fix(deploy): HOST 部署回收同槽遗留进程，避免旧实例占端口 |
| `6a899f6` | docs | docs: 归档 HOST 部署遗留进程回收修复 |
| `7f9294c` | bug | fix(pipeline): 健康检查补首页冒烟，页面 5xx 不再假通过 |
| `2702dec` | docs | docs: 归档健康检查首页冒烟修复 |
| `52ef7e5` | feat | feat(pipeline): 部署产物固化端口/镜像/探活路径证据 |
| `dd45b7c` | chore | test(pipeline): 补部署证据链字段用例 |
| `36cb5a9` | docs | docs: 归档部署证据链字段 |
| `d2846c3` | feat | feat(sandbox): 接入 gVisor 运行时并加固缓存卷初始化容器 |
| `aa2a6a8` | docs | docs: 归档沙箱 gVisor 运行时接入 |
| `a83d4ff` | feat | feat(sandbox): 镜像构建切换到 BuildKit(buildx build --load) |
| `97f91d6` | chore | test(scaffold): 断言 image_package 配方走 BuildKit(buildx) |
| `f816a5d` | docs | docs: 归档镜像构建切换到 BuildKit |
| `e6380aa` | feat | feat(sandbox): Docker CLI 抽象，部署/中间件/巡检可切换 podman |
| `5c69021` | chore | chore(config): 沙箱运行时/初始化镜像/docker 二进制配置项 |
| `dd8fd33` | chore | test(sandbox): 断言中间件 run 命令跟随配置的 docker 二进制 |
| `8a19091` | docs | docs: 归档 Docker CLI 可切换 podman |
| `854f4cc` | bug | fix(archive): 新增用例数按引入测试的提交统计，多提交会话不再误判为 0 |

## 测试与质量总结
- 命令：`mvn -B test -Djacoco.skip=true -Dtest='!ProcessArchiveTest,!DeployEnvPrecheckTest,!DeployRecipeFlowTest,!DockerDaemonRepairTest' -Dsurefire.failIfNoSpecifiedTests=false`
- 结果：PASSED；Tests run: 602, Failures: 0, Errors: 0, Skipped: 4
- 新增用例：32
- 行覆盖率：38.0%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 推送

## 链接
- 归档：`docs/process/chore/2026-11-18_0850_merge-remote-snapshot/manifest.json`
- 上一份同域总结：worktree-commit-hook
