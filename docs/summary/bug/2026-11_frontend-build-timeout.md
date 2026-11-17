---
canonical: docs/summary/2026-11/2026-11-18_0008_frontend-build-timeout.md
schema: session-summary/v1
period: 2026-11
createdAt: 2026-11-18T00:28:17+08:00
extends: workspace-supervisor-stream-ask
refs:
  - bug/2026-11-18_0008_frontend-build-timeout
metrics:
  sessions: 1
  casesAdded: 1
  casesTotal: 602
  passRate: "100%"
  lineCoverage: "38.9%"
  coverageDelta: "N/A"
  patchCoverage: "N/A"
  gate: PASS
---

# 前端构建超时：npm 镜像与 pnpm store 缓存

## 概述
run-50087672 前端阶段 E-302：pnpm install 冷下载（reused 0，2~43KiB/s，单请求 14~20s）占满 10m 预算，build 只剩 294s 被终止；沙箱内直连 npmjs 过慢且 pnpm store 不在缓存卷
DockerSandbox 注入 npm_config_registry（默认 npmmirror，可用 AGENT_SANDBOX_NPM_REGISTRY 覆盖）并同时设置 COREPACK_NPM_REGISTRY；脚手架前端构建超时 10m→20m、pnpm install 加 --prefer-offline；package.json 固定 packageManager=pnpm@9.15.4；现有项目 actions.yml 同步放宽预算

## 交付清单
| 提交 | 类别 | 说明 |
|---|---|---|
| `769dd14` | bug | fix(pipeline): 前端构建注入 npm 镜像与 pnpm store，放宽冷安装预算 |

## 测试与质量总结
- 命令：`mvn -B -pl spec-checks -am test`
- 结果：PASSED；Tests run: 602, Failures: 0, Errors: 0, Skipped: 2
- 新增用例：1
- 行覆盖率：38.9%（JaCoCo；patch coverage 需按 diff 统计，暂记 N/A）
- 质量门禁：**PASS**

## 风险敞口
- 未覆盖的新增文件：无
- 跳过用例：见 evidence 中 Skipped 计数

## 遗留与建议
- 无

## 链接
- 归档：`docs/process/bug/2026-11-18_0008_frontend-build-timeout/manifest.json`
- 上一份同域总结：workspace-supervisor-stream-ask
