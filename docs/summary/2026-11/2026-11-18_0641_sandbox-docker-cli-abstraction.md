---
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-18T07:08:03+08:00
extends: worktree-and-gate-fixes
refs:
  - feat/2026-11-18_0641_sandbox-docker-cli-abstraction
metrics:
  sessions: 1
  casesAdded: 4
  casesTotal: 614
  passRate: "100%"
  lineCoverage: "39.0%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# Docker CLI 可切换为 podman（去 Docker Desktop 硬依赖）

## 概述
沙箱外仍硬编码 docker：部署器/中间件自愈/运行时巡检/流水线引擎全部调用 docker CLI，换 Podman 或 rootless 环境只有构建沙箱生效
抽出 DockerCli 作为可执行文件的唯一来源（agent.sandbox.docker-binary），PipelineConfig 注入 DockerDeployer/AppRuntimeInspector/DockerEnvRuntime/DockerEnvProvisioner/BuiltInPipelineEngine；各构造器保留无参重载走默认 docker

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `e6380aa` | feat | feat(sandbox): Docker CLI 抽象，部署/中间件/巡检可切换 podman |
| `5c69021` | chore | chore(config): 沙箱运行时/初始化镜像/docker 二进制配置项 |
| `dd8fd33` | chore | test(sandbox): 断言中间件 run 命令跟随配置的 docker 二进制 |

## 测试与质量总结
- 命令：`mvn -B -pl spec-checks -am test`
- 结果：PASSED；Tests run: 614, Failures: 0, Errors: 0, Skipped: 2
- 新增用例：4
- 行覆盖率：39.0%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/feat/2026-11-18_0641_sandbox-docker-cli-abstraction/manifest.json`
- 上一份同域总结：worktree-and-gate-fixes
